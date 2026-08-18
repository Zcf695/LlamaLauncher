package com.llama.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Llama Server 前台服务：
 * - 管理 llama-server 进程生命周期
 * - 持有唤醒锁防止休眠
 * - 监控进程状态和内存占用
 * - 通过广播与 Activity 通信
 */
class LlamaServerService : Service() {

    private var serverProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorJob: Job? = null
    private var currentMemoryKb = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer(intent)
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer(intent: Intent) {
        val binaryPath = intent.getStringExtra(EXTRA_BINARY_PATH) ?: return
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: return
        val threads = intent.getIntExtra(EXTRA_THREADS, 4)
        val contextSize = intent.getIntExtra(EXTRA_CONTEXT, 8192)
        val maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 512)
        val port = intent.getIntExtra(EXTRA_PORT, 8080)
        val enableJinja = intent.getBooleanExtra(EXTRA_JINJA, true)
        val enableCacheRot = intent.getBooleanExtra(EXTRA_CACHE_ROT, false)
        val enableMlock = intent.getBooleanExtra(EXTRA_MLOCK, true)
        val enableWakeLock = intent.getBooleanExtra(EXTRA_WAKE_LOCK, true)

        // 构建命令
        val cmd = mutableListOf(
            binaryPath,
            "-m", modelPath,
            "-c", contextSize.toString(),
            "-t", threads.toString(),
            "--n-predict", maxTokens.toString(),
            "--host", "0.0.0.0",
            "--port", port.toString(),
        )
        if (enableJinja) cmd.add("--jinja")
        if (enableCacheRot) cmd.add("--cache-all-rot")
        if (enableMlock) cmd.add("--mlock")

        // 唤醒锁
        if (enableWakeLock) {
            acquireWakeLock()
        }

        // 启动前台通知
        startForeground(NOTIFICATION_ID, buildNotification("正在启动 llama-server..."))

        // 启动进程
        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            serverProcess = pb.start()

            // 读取输出
            CoroutineScope(Dispatchers.IO).launch {
                val reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { broadcastLog(it) }
                }
            }

            // 状态监控
            startMonitoring()

            broadcastStatus(STATUS_RUNNING)
        } catch (e: Exception) {
            broadcastStatus(STATUS_ERROR, e.message ?: "启动失败")
            stopSelf()
        }
    }

    private fun stopServer() {
        monitorJob?.cancel()
        serverProcess?.destroy()
        serverProcess = null
        releaseWakeLock()
        broadcastStatus(STATUS_STOPPED)
        stopSelf()
    }

    private fun startMonitoring() {
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && serverProcess != null) {
                try {
                    // 检查进程是否存活
                    val pid = getServerPid()
                    if (pid > 0) {
                        currentMemoryKb = getProcessMemory(pid)
                        broadcastMemory(currentMemoryKb)
                        updateNotification("llama-server 运行中 | 内存: ${formatMemory(currentMemoryKb)}")
                    }

                    // 检查进程是否退出
                    serverProcess?.let {
                        if (!it.isAlive) {
                            broadcastStatus(STATUS_STOPPED, "进程已退出")
                            stopSelf()
                            return@launch
                        }
                    }
                } catch (_: Exception) {}
                delay(2000)
            }
        }
    }

    private fun getServerPid(): Int {
        return try {
            val field = Process::class.java.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(serverProcess)
        } catch (_: Exception) {
            -1
        }
    }

    private fun getProcessMemory(pid: Int): Long {
        return try {
            val file = java.io.File("/proc/$pid/status")
            if (file.exists()) {
                file.readLines().forEach { line ->
                    if (line.startsWith("VmRSS:")) {
                        return line.split(Regex("\\s+"))[1].toLong()
                    }
                }
            }
            0
        } catch (_: Exception) {
            0
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LlamaLauncher::ServerWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Llama Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "llama.cpp 模型服务运行状态"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Llama 启动器")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun broadcastStatus(status: String, message: String = "") {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    private fun broadcastMemory(memoryKb: Long) {
        val intent = Intent(BROADCAST_MEMORY).apply {
            putExtra(EXTRA_MEMORY_KB, memoryKb)
        }
        sendBroadcast(intent)
    }

    private fun broadcastLog(line: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            putExtra(EXTRA_LOG, line)
        }
        sendBroadcast(intent)
    }

    private fun formatMemory(kb: Long): String {
        return when {
            kb >= 1024 * 1024 -> String.format("%.1f GB", kb / (1024.0 * 1024))
            kb >= 1024 -> String.format("%.1f MB", kb / 1024.0)
            else -> "$kb KB"
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.llama.launcher.START"
        const val ACTION_STOP = "com.llama.launcher.STOP"

        const val EXTRA_BINARY_PATH = "binary_path"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_CONTEXT = "context"
        const val EXTRA_MAX_TOKENS = "max_tokens"
        const val EXTRA_PORT = "port"
        const val EXTRA_JINJA = "jinja"
        const val EXTRA_CACHE_ROT = "cache_rot"
        const val EXTRA_MLOCK = "mlock"
        const val EXTRA_WAKE_LOCK = "wake_lock"

        const val STATUS_STOPPED = "stopped"
        const val STATUS_STARTING = "starting"
        const val STATUS_RUNNING = "running"
        const val STATUS_ERROR = "error"

        const val BROADCAST_STATUS = "com.llama.launcher.STATUS"
        const val BROADCAST_MEMORY = "com.llama.launcher.MEMORY"
        const val BROADCAST_LOG = "com.llama.launcher.LOG"

        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_MEMORY_KB = "memory_kb"
        const val EXTRA_LOG = "log"

        private const val CHANNEL_ID = "llama_server_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, binaryPath: String, settings: SettingsManager) {
            val intent = Intent(context, LlamaServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_BINARY_PATH, binaryPath)
                putExtra(EXTRA_MODEL_PATH, settings.modelPath)
                putExtra(EXTRA_THREADS, settings.threads)
                putExtra(EXTRA_CONTEXT, settings.contextSize)
                putExtra(EXTRA_MAX_TOKENS, settings.maxTokens)
                putExtra(EXTRA_PORT, settings.port)
                putExtra(EXTRA_JINJA, settings.enableJinja)
                putExtra(EXTRA_CACHE_ROT, settings.enableCacheRot)
                putExtra(EXTRA_MLOCK, settings.enableMlock)
                putExtra(EXTRA_WAKE_LOCK, settings.enableWakeLock)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LlamaServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
