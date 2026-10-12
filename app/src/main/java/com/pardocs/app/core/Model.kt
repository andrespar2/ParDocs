package com.pardocs.app.core

import java.io.File

/**
 * A file that has been imported into the app sandbox so that it can be processed
 * reliably (SAF URIs may become unreadable once the activity result is consumed).
 */
data class WorkFile(
    val file: File,
    val displayName: String,
    val mimeType: String?,
) {
    val extension: String = displayName.substringAfterLast('.', "").lowercase()

    val sizeBytes: Long get() = file.length()
}
