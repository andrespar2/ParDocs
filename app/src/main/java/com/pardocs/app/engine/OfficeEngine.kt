package com.pardocs.app.engine

import android.content.Context
import com.pardocs.app.core.Formats
import com.pardocs.app.core.Storage
import com.pardocs.app.core.WorkFile
import java.io.File

/**
 * Document intake: Word / Excel / PowerPoint (OOXML), TXT, CSV, HTML, RTF.
 * Everything runs locally. Legacy binary formats (.doc/.xls/.ppt) are rejected
 * with a clear explanation instead of silently producing garbage.
 */
object OfficeEngine {

    fun extractText(workFile: WorkFile): String {
        val extension = workFile.extension
        return when {
            extension in Formats.OOXML -> OoxmlExtractor.extract(workFile.file)
            extension in Formats.PLAIN_TEXT -> TextEngine.plainText(workFile.file)
            extension in Formats.HTML -> TextEngine.htmlText(workFile.file)
            extension in Formats.RTF -> TextEngine.rtfText(workFile.file)
            extension in Formats.LEGACY_OFFICE -> throw UnsupportedFormatException(
                Formats.describeUnsupportedDocument(extension)
                    ?: "Formato binario heredado no soportado."
            )
            extension == Formats.PDF -> throw UnsupportedFormatException(
                "Usa la herramienta «PDF → Texto» del apartado PDF."
            )
            else -> throw UnsupportedFormatException(
                "No se puede extraer texto de «${workFile.displayName}» (.$extension)."
            )
        }
    }

    suspend fun toPlainText(
        workFile: WorkFile,
        outFile: File,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        onProgress(0.3f, "Extrayendo texto")
        val text = extractText(workFile)
        if (text.isBlank()) throw EngineException("«${workFile.displayName}» no contiene texto legible.")
        onProgress(0.85f, "Guardando el texto")
        outFile.writeText(text, Charsets.UTF_8)
        return EngineOutcome(
            listOf(outFile),
            stats = mapOf("Caracteres" to text.length.toString()),
        )
    }

    /**
     * Converts documents to PDF. HTML can be rendered with its original layout
     * (WebView print pipeline); every other format is converted through its
     * extracted text. The method is explicit about which path it used.
     */
    suspend fun toPdf(
        context: Context,
        workFile: WorkFile,
        outFile: File,
        renderHtmlWithLayout: Boolean,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        val extension = workFile.extension
        if (extension in Formats.HTML && renderHtmlWithLayout) {
            onProgress(0.2f, "Leyendo el HTML")
            val html = TextEngine.decodeText(workFile.file.readBytes())
            val rendered = HtmlToPdf.convert(context, html, outFile, onProgress)
            if (rendered) {
                return EngineOutcome(
                    listOf(outFile),
                    stats = mapOf("Origen" to "HTML renderizado (WebView)", "Tamaño" to Storage.formatSize(outFile.length())),
                )
            }
            onProgress(0.5f, "El renderizador HTML falló; usando texto")
        }
        val text = extractText(workFile)
        onProgress(0.4f, "Maquetando «${workFile.displayName}»")
        val result = PdfRender.textToPdf(text, workFile.displayName.substringBeforeLast('.'), outFile, onProgress)
        val flavor = if (extension in Formats.HTML) "HTML → texto → PDF" else "texto extraído → PDF"
        return EngineOutcome(result.outputs, stats = result.stats + mapOf("Origen" to flavor))
    }
}