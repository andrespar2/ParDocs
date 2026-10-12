package com.pardocs.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything that talks to the Storage Access Framework and to other apps.
 * All user files are copied into the app sandbox before processing so that a
 * revoked URI can never break a running operation.
 */
object SafFiles {

    data class DocumentMeta(val name: String, val size: Long, val mimeType: String?)

    fun query(context: Context, uri: Uri): DocumentMeta {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val resolvedName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "archivo"
        return DocumentMeta(resolvedName, size, context.contentResolver.getType(uri))
    }

    fun importTo(context: Context, uri: Uri, sessionDir: File, index: Int): WorkFile {
        val meta = query(context, uri)
        val extension = Formats.extensionOf(meta.name)
        val target = Storage.uniqueFile(sessionDir, "in${index}_${meta.name.substringBeforeLast('.', meta.name)}", extension)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: throw IllegalStateException("No se pudo leer «${meta.name}»")
        return WorkFile(target, meta.name, meta.mimeType ?: Formats.mimeOf(extension))
    }

    suspend fun importAll(
        context: Context,
        uris: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<WorkFile> = withContext(Dispatchers.IO) {
        val sessionDir = Storage.newWorkSession(context)
        val imported = ArrayList<WorkFile>(uris.size)
        uris.forEachIndexed { index, uri ->
            runCatching { importTo(context, uri, sessionDir, index) }
                .onSuccess { imported += it }
            onProgress(index + 1, uris.size)
        }
        imported
    }

    /** Streams an app-sandbox file into a location chosen by the user through SAF. */
    suspend fun exportTo(context: Context, target: Uri, source: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } != null
        }.getOrDefault(false)
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun mimeFor(file: File): String = Formats.mimeOf(file.extension.lowercase())

    fun shareFiles(context: Context, files: List<File>, chooserTitle: String = "Compartir con") {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = sharableCommonMime(files)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openFile(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), mimeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun sharableCommonMime(files: List<File>): String {
        val families = files.map { mimeFor(it).substringBefore('/') }.distinct()
        return if (families.size == 1) "${families.first()}/*" else "*/*"
    }
}
