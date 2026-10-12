package com.pardocs.app.core

/**
 * Central catalogue of the formats ParDocs understands, and — more importantly —
 * of the formats it can actually produce locally.
 *
 * Anything that is decodable but not encodable is reported to the user instead of
 * being silently simulated.
 */
object Formats {

    const val PDF = "pdf"

    /** Formats Android / our codecs can decode into a Bitmap. */
    val DECODABLE_IMAGES = setOf(
        "jpg", "jpeg", "jpe", "png", "webp", "bmp", "dib", "tif", "tiff",
        "heic", "heif", "gif", "avif", "ico",
    )

    /** Formats we can honestly write on-device. */
    val ENCODABLE_IMAGES = setOf("jpg", "jpeg", "png", "webp", "bmp", "tiff")

    /** Decodable only through a codec of our own. */
    val CODEC_IMAGES = setOf("bmp", "dib", "tif", "tiff")

    /** Read-only formats: we explain the limitation for these. */
    val READ_ONLY_IMAGES = setOf("heic", "heif", "gif", "avif", "ico")

    val OOXML_DOCS = setOf("docx")
    val OOXML_SHEETS = setOf("xlsx")
    val OOXML_SLIDES = setOf("pptx")
    val OOXML = OOXML_DOCS + OOXML_SHEETS + OOXML_SLIDES

    val LEGACY_OFFICE = setOf("doc", "xls", "ppt", "pps", "odt", "ods", "odp")

    val PLAIN_TEXT = setOf("txt", "text", "md", "markdown", "log", "csv", "tsv", "json", "xml", "yaml", "yml", "ini", "srt", "vtt")

    val HTML = setOf("html", "htm", "xhtml")
    val RTF = setOf("rtf")

    enum class Category { PDF, IMAGE, DOCUMENT, OTHER }

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun categoryOf(extension: String): Category = when (extension) {
        PDF -> Category.PDF
        in DECODABLE_IMAGES -> Category.IMAGE
        in OOXML, in LEGACY_OFFICE, in PLAIN_TEXT, in HTML, in RTF -> Category.DOCUMENT
        else -> Category.OTHER
    }

    val ALL_DOCUMENT_EXTENSIONS = OOXML + LEGACY_OFFICE + PLAIN_TEXT + HTML + RTF

    fun mimeOf(extension: String): String = when (extension) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "bmp", "dib" -> "image/bmp"
        "tif", "tiff" -> "image/tiff"
        "heic", "heif" -> "image/heif"
        "gif" -> "image/gif"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "doc" -> "application/msword"
        "xls" -> "application/vnd.ms-excel"
        "ppt" -> "application/vnd.ms-powerpoint"
        "txt", "text", "log", "srt", "vtt", "ini" -> "text/plain"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "yaml", "yml" -> "text/yaml"
        "html", "htm", "xhtml" -> "text/html"
        "rtf" -> "application/rtf"
        else -> "application/octet-stream"
    }

    fun supportsImageEncoding(extension: String): Boolean = extension in ENCODABLE_IMAGES

    fun describeUnsupportedImageTarget(extension: String): String? = when (extension) {
        "heic", "heif" ->
            "Android no expone un codificador HEIC público: se puede leer HEIC pero no generarlo. Elige JPG, PNG, WEBP, BMP o TIFF."
        "avif" ->
            "Android no expone un codificador AVIF público: se puede leer AVIF (Android 12+) pero no generarlo. Elige JPG, PNG, WEBP, BMP o TIFF."
        "gif" ->
            "La escritura de GIF animado no está disponible localmente. Elige JPG, PNG, WEBP, BMP o TIFF."
        else -> null
    }

    fun describeUnsupportedDocument(extension: String): String? = when (extension) {
        in LEGACY_OFFICE ->
            "Los formatos binarios heredados (.$extension) usan el contenedor OLE propietario y no pueden procesarse localmente. Guarda el documento como .docx/.xlsx/.pptx y vuelve a intentarlo."
        else -> null
    }
}
