package com.llama.launcher

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 模型扫描器：自动查找手机中的 GGUF 模型文件。
 */
class ModelScanner(private val context: Context) {

    data class ModelFile(
        val path: String,
        val name: String,
        val size: Long
    )

    /**
     * 扫描常用目录中的 GGUF 文件。
     */
    fun scan(): List<ModelFile> {
        val models = mutableListOf<ModelFile>()
        val searchDirs = getSearchDirectories()

        for (dir in searchDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            findGgufFiles(dir, models, depth = 0)
        }

        return models.distinctBy { it.path }.sortedByDescending { it.size }
    }

    private fun getSearchDirectories(): List<File> {
        val dirs = mutableListOf<File>()

        // 公共 Download 目录
        dirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))

        // 应用专属目录
        dirs.add(File(context.getExternalFilesDir(null), "models"))

        // 根目录的 models 文件夹
        val externalStorage = Environment.getExternalStorageDirectory()
        dirs.add(File(externalStorage, "models"))
        dirs.add(File(externalStorage, "llama.cpp/models"))
        dirs.add(File(externalStorage, "Download"))

        return dirs
    }

    private fun findGgufFiles(dir: File, results: MutableList<ModelFile>, depth: Int) {
        if (depth > 3) return // 限制搜索深度

        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                findGgufFiles(file, results, depth + 1)
            } else if (file.name.endsWith(".gguf", ignoreCase = true)) {
                results.add(
                    ModelFile(
                        path = file.absolutePath,
                        name = file.name,
                        size = file.length()
                    )
                )
            }
        }
    }

    /**
     * 格式化文件大小。
     */
    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
