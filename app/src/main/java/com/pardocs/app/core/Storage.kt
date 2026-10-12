package com.pardocs.app.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where ParDocs keeps its working copies and its generated documents. */
object Storage {

    private const val WORK_DIR = "work"
    private const val OUTPUT_DIR = "output"

    /** User visible output. Uses app-specific external storage: no permission required. */
    fun outputDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, OUTPUT_DIR).apply { mkdirs() }
    }

    /** Scratch space for imported inputs and intermediate results. */
    fun workDir(context: Context): File = File(context.cacheDir, WORK_DIR).apply { mkdirs() }

    fun newWorkSession(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(workDir(context), "session_$stamp").apply { mkdirs() }
    }

    /** Returns a non-existing file inside [dir] named `base.ext`, `base (2).ext`, ... */
    fun uniqueFile(dir: File, base: String, extension: String): File {
        dir.mkdirs()
        val safeBase = base.ifBlank { "documento" }.replace(Regex("""[\\/:*?"<>|]"""), "_").take(80)
        val suffix = if (extension.isBlank()) "" else ".$extension"
        var candidate = File(dir, "$safeBase$suffix")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(dir, "$safeBase ($counter)$suffix")
            counter++
        }
        return candidate
    }

    fun clearWork(context: Context) {
        workDir(context).listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { if (it.name.startsWith("extract_")) it.deleteRecursively() }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format(Locale.US, if (value < 10) "%.1f %s" else "%.0f %s", value, units[index])
    }
}
