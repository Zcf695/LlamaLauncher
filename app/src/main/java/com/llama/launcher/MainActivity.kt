package com.llama.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.llama.launcher.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager
    private var selectedModelPath: String? = null

    // 文件选择器
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { copyModelToAppStorage(it) }
    }
    private var isRunning = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LlamaServerService.BROADCAST_STATUS -> handleStatus(intent)
                LlamaServerService.BROADCAST_MEMORY -> handleMemory(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)
        updateModelDisplay()

        setupUI()
        loadSettings()
        checkPermission()
        registerReceiver()
    }

    private fun setupUI() {
        // 上下文窗口选项
        val contextOptions = listOf(2048, 4096, 8192, 16384, 32768, 65536)
        val contextAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            contextOptions.map { "${it / 1024}K" }
        )
        contextAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.contextSpinner.adapter = contextAdapter

        // 线程数滑块
        binding.threadsSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(1)
                binding.threadsValue.text = value.toString()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // 选择模型按钮
        binding.scanButton.setOnClickListener {
            openFilePicker()
        }

        // 启动/停止按钮
        binding.startButton.setOnClickListener {
            if (isRunning) {
                LlamaServerService.stop(this)
            } else {
                startServer()
            }
        }

        // 聊天按钮
        binding.chatButton.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }
    }

    private fun loadSettings() {
        binding.threadsSeekBar.progress = settings.threads
        binding.threadsValue.text = settings.threads.toString()
        binding.maxTokensEdit.setText(settings.maxTokens.toString())
        binding.portEdit.setText(settings.port.toString())
        binding.jinjaSwitch.isChecked = settings.enableJinja
        binding.cacheRotSwitch.isChecked = settings.enableCacheRot
        binding.mlockSwitch.isChecked = settings.enableMlock
        binding.wakeLockSwitch.isChecked = settings.enableWakeLock

        val contextOptions = listOf(2048, 4096, 8192, 16384, 32768, 65536)
        val ctxIndex = contextOptions.indexOf(settings.contextSize).coerceAtLeast(0)
        binding.contextSpinner.setSelection(ctxIndex)

        if (settings.modelPath.isNotEmpty()) {
            binding.modelSpinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_item,
                listOf(File(settings.modelPath).name)
            )
        }

        updateIpAddress()
    }

    private fun saveSettings() {
        settings.threads = binding.threadsSeekBar.progress.coerceAtLeast(1)
        val contextOptions = listOf(2048, 4096, 8192, 16384, 32768, 65536)
        settings.contextSize = contextOptions[binding.contextSpinner.selectedItemPosition]
        settings.maxTokens = binding.maxTokensEdit.text.toString().toIntOrNull() ?: 512
        settings.port = binding.portEdit.text.toString().toIntOrNull() ?: 8080
        settings.enableJinja = binding.jinjaSwitch.isChecked
        settings.enableCacheRot = binding.cacheRotSwitch.isChecked
        settings.enableMlock = binding.mlockSwitch.isChecked
        settings.enableWakeLock = binding.wakeLockSwitch.isChecked

        selectedModelPath?.let { settings.modelPath = it }
    }

    private fun openFilePicker() {
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    private fun copyModelToAppStorage(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    if (nameIndex >= 0) cursor.getString(nameIndex) else "model.gguf"
                } ?: "model.gguf"

                val modelsDir = File(getExternalFilesDir(null), "models")
                if (!modelsDir.exists()) modelsDir.mkdirs()
                val destFile = File(modelsDir, fileName)

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                selectedModelPath = destFile.absolutePath
                settings.modelPath = destFile.absolutePath

                withContext(Dispatchers.Main) {
                    updateModelDisplay()
                    Toast.makeText(this@MainActivity, "模型已选择: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "选择失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateModelDisplay() {
        val path = settings.modelPath
        if (path.isNotEmpty()) {
            val file = File(path)
            val sizeStr = if (file.exists()) {
                Formatter.formatFileSize(this@MainActivity, file.length())
            } else "文件不存在"
            binding.modelSpinner.adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_item,
                listOf("${file.name} ($sizeStr)")
            )
            binding.modelSpinner.setSelection(0)
        }
    }

    private fun startServer() {
        saveSettings()

        if (settings.modelPath.isEmpty()) {
            Toast.makeText(this, "请先选择模型文件", Toast.LENGTH_SHORT).show()
            return
        }

        val binaryPath = ensureBinary()
        if (binaryPath == null) {
            Toast.makeText(this, "llama-server 二进制文件解压失败", Toast.LENGTH_LONG).show()
            return
        }

        LlamaServerService.start(this, binaryPath, settings)
        updateStatus(LlamaServerService.STATUS_STARTING)
    }

    private fun ensureBinary(): String? {
        val destFile = File(filesDir, "llama-server")
        // 如果已存在且大小>0，直接返回
        if (destFile.exists() && destFile.length() > 0) {
            destFile.setExecutable(true)
            return destFile.absolutePath
        }
        // 从 assets 复制
        return try {
            assets.open("llama-server").use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile.setExecutable(true)
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun handleStatus(intent: Intent) {
        val status = intent.getStringExtra(LlamaServerService.EXTRA_STATUS) ?: return
        val message = intent.getStringExtra(LlamaServerService.EXTRA_MESSAGE) ?: ""
        updateStatus(status, message)
    }

    private fun handleMemory(intent: Intent) {
        val memoryKb = intent.getLongExtra(LlamaServerService.EXTRA_MEMORY_KB, 0)
        if (memoryKb > 0) {
            val mb = memoryKb / 1024
            binding.memoryText.text = "内存: ${mb} MB"
        }
    }

    private fun updateStatus(status: String, message: String = "") {
        when (status) {
            LlamaServerService.STATUS_RUNNING -> {
                isRunning = true
                binding.statusText.text = getString(R.string.status_running)
                binding.statusIndicator.setBackgroundColor(getColor(R.color.accent_green))
                binding.startButton.text = getString(R.string.btn_stop)
                binding.startButton.setBackgroundTintList(getColorStateList(R.color.accent_red))
                binding.chatButton.isEnabled = true
            }
            LlamaServerService.STATUS_STARTING -> {
                isRunning = false
                binding.statusText.text = getString(R.string.status_starting)
                binding.statusIndicator.setBackgroundColor(getColor(R.color.accent_yellow))
                binding.startButton.isEnabled = false
            }
            LlamaServerService.STATUS_STOPPED -> {
                isRunning = false
                binding.statusText.text = getString(R.string.status_stopped)
                binding.statusIndicator.setBackgroundColor(getColor(R.color.text_secondary))
                binding.startButton.text = getString(R.string.btn_start)
                binding.startButton.setBackgroundTintList(getColorStateList(R.color.accent_green))
                binding.startButton.isEnabled = true
                binding.chatButton.isEnabled = false
                binding.memoryText.text = "内存: -- MB"
            }
            LlamaServerService.STATUS_ERROR -> {
                isRunning = false
                binding.statusText.text = "${getString(R.string.status_error)}: $message"
                binding.statusIndicator.setBackgroundColor(getColor(R.color.accent_red))
                binding.startButton.text = getString(R.string.btn_start)
                binding.startButton.setBackgroundTintList(getColorStateList(R.color.accent_green))
                binding.startButton.isEnabled = true
                binding.chatButton.isEnabled = false
            }
        }
    }

    private fun updateIpAddress() {
        val ip = getLocalIpAddress()
        binding.ipAddressText.text = "IP: $ip"
        val port = settings.port
        binding.apiUrlText.text = "API: http://$ip:$port/v1"
    }

    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return sAddr
                        }
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    private fun checkPermission() {
        // 通知权限（Android 13+ 必须，前台服务需要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_PERMISSIONS
                )
            }
        }
        // 存储权限（Android 12 及以下）
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQUEST_PERMISSIONS
                )
            }
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(LlamaServerService.BROADCAST_STATUS)
            addAction(LlamaServerService.BROADCAST_MEMORY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
