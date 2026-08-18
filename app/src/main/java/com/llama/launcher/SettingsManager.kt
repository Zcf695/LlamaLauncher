package com.llama.launcher

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置管理器：保存和读取 llama-server 运行参数。
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("llama_launcher", Context.MODE_PRIVATE)

    var modelPath: String
        get() = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL_PATH, value).apply()

    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 4)
        set(value) = prefs.edit().putInt(KEY_THREADS, value).apply()

    var contextSize: Int
        get() = prefs.getInt(KEY_CONTEXT, 8192)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    var maxTokens: Int
        get() = prefs.getInt(KEY_MAX_TOKENS, 512)
        set(value) = prefs.edit().putInt(KEY_MAX_TOKENS, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var enableJinja: Boolean
        get() = prefs.getBoolean(KEY_JINJA, true)
        set(value) = prefs.edit().putBoolean(KEY_JINJA, value).apply()

    var enableCacheRot: Boolean
        get() = prefs.getBoolean(KEY_CACHE_ROT, false)
        set(value) = prefs.edit().putBoolean(KEY_CACHE_ROT, value).apply()

    var enableMlock: Boolean
        get() = prefs.getBoolean(KEY_MLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_MLOCK, value).apply()

    var enableWakeLock: Boolean
        get() = prefs.getBoolean(KEY_WAKE_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_LOCK, value).apply()

    /**
     * 构建 llama-server 启动命令。
     */
    fun buildCommand(binaryPath: String): List<String> {
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

        return cmd
    }

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_THREADS = "threads"
        private const val KEY_CONTEXT = "context"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_PORT = "port"
        private const val KEY_JINJA = "jinja"
        private const val KEY_CACHE_ROT = "cache_rot"
        private const val KEY_MLOCK = "mlock"
        private const val KEY_WAKE_LOCK = "wake_lock"
    }
}
