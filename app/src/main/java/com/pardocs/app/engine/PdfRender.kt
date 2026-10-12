package com.pardocs.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pardocs.app.core.Storage
import com.pardocs.app.core.WorkFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Everything that renders PDFs and builds new PDFs using Android's own stack
 * (PdfRenderer / PdfDocument). Kept separate from [PdfEngine] so that the
 * structural toolkit has no hidden rendering dependency.
 */
object PdfRender {

    data class RenderOptions(
        val format: String = "jpg",
        val dpi: Int = 150,
        val quality: Int = 90,
    )

    private const val MAX_RENDER_DIMENSION = 5600

    // --------------------------------------------------------- PDF -> images

    suspend fun toImages(
        file: WorkFile,
        outDir: File,
        options: RenderOptions,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        val outputs = mutableListOf<File>()
        val base = file.displayName.substringBeforeLast('.')
        withRenderer(file.file) { renderer ->
            val count = renderer.pageCount
            for (index in 0 until count) {
                                onProgress(index.toFloat() / count.toFloat(), "Renderizando página ${index + 1} de $count")
                val page = renderer.openPage(index)
                val scale = computeScale(page.width.toFloat(), page.height.toFloat(), options.dpi)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                val target = Storage.uniqueFile(outDir, "${base}_pag${index + 1}", options.format)
                ImageEngine.encode(bitmap, target, options.format, options.quality)
                bitmap.recycle()
                outputs += target
            }
        }
        return EngineOutcome(outputs, stats = mapOf("Imágenes" to outputs.size.toString()))
    }

    // ------------------------------------------------------------- compress

    /**
     * Real compression by re-rasterising at a lower resolution and re-encoding
     * to JPEG. This is the honest "fuerte" strategy: it shrinks scans but
     * removes selectable text — the UI says so before the user presses the button.
     */
    suspend fun compressRasterized(
        file: WorkFile,
        outFile: File,
        dpi: Int,
        quality: Int,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        val originalSize = file.file.length()
        withRenderer(file.file) { renderer ->
            val count = renderer.pageCount
            val document = PdfDocument()
            val paint = Paint().apply { isFilterBitmap = true; isAntiAlias = false }
            try {
                for (index in 0 until count) {
                    onProgress(index.toFloat() / count.toFloat(), "Comprimiendo página ${index + 1} de $count")
                    val page = renderer.openPage(index)
                    val scale = computeScale(page.width.toFloat(), page.height.toFloat(), dpi)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    rendered.eraseColor(Color.WHITE)
                    page.render(rendered, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // Opaque bitmap -> PdfDocument embeds real JPEG, which is what keeps the file small.
                    val opaque = toOpaque(rendered)
                    rendered.recycle()

                    val pageInfo = PdfDocument.PageInfo.Builder(page.width.toInt(), page.height.toInt(), index + 1).create()
                    val pdfPage = document.startPage(pageInfo)
                    pdfPage.canvas.drawBitmap(opaque, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), paint)
                    document.finishPage(pdfPage)
                    opaque.recycle()
                }
                onProgress(0.95f, "Escribiendo el PDF comprimido")
                FileOutputStream(outFile).use { document.writeTo(it) }
            } finally {
                document.close()
            }
        }
        val newSize = outFile.length()
        val warnings = mutableListOf<String>()
        if (newSize >= originalSize) {
            warnings += "El PDF comprimido no es más pequeño que el original (${Storage.formatSize(newSize)}). El archivo original ya estaba optimizado."
        }
        return EngineOutcome(
            listOf(outFile),
            warnings = warnings,
            stats = mapOf(
                "Antes" to Storage.formatSize(originalSize),
                "Después" to Storage.formatSize(newSize),
                "Reducción" to reductionLabel(originalSize, newSize),
            ),
        )
    }

    // ------------------------------------------------------ images -> PDF

    suspend fun imagesToPdf(
        images: List<WorkFile>,
        outFile: File,
        pageSizeMode: String, // "A4" | "image"
        marginPt: Int,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        if (images.isEmpty()) throw EngineException("Selecciona al menos una imagen.")
        val document = PdfDocument()
        val paint = Paint().apply { isFilterBitmap = true; isAntiAlias = false }
        try {
            images.forEachIndexed { index, image ->
                checkCancelled()
                onProgress(index.toFloat() / images.size.toFloat(), "Añadiendo ${image.displayName}")
                val bitmap = ImageEngine.decode(image.file, maxDimension = 8192)
                try {
                    val opaque = toOpaque(bitmap)
                    val (pageWidth, pageHeight, dst) = if (pageSizeMode == "A4") {
                        val a4w = 595f
                        val a4h = 842f
                        val margin = marginPt.toFloat()
                        val maxW = a4w - margin * 2f
                        val maxH = a4h - margin * 2f
                        val scale = minOf(maxW / opaque.width, maxH / opaque.height, 1f).coerceAtLeast(0.01f)
                        val w = opaque.width * scale
                        val h = opaque.height * scale
                        val left = (a4w - w) / 2f
                        val top = (a4h - h) / 2f
                        Triple(a4w, a4h, RectF(left, top, left + w, top + h))
                    } else {
                        val w = opaque.width.toFloat()
                        val h = opaque.height.toFloat()
                        Triple(w, h, RectF(0f, 0f, w, h))
                    }
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), index + 1).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(opaque, null, dst, paint)
                    document.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            onProgress(0.95f, "Escribiendo el PDF")
            FileOutputStream(outFile).use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return EngineOutcome(
            listOf(outFile),
            stats = mapOf("Imágenes" to images.size.toString(), "Tamaño" to Storage.formatSize(outFile.length())),
        )
    }

    // --------------------------------------------------------- text -> PDF

    suspend fun textToPdf(
        text: String,
        title: String,
        outFile: File,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        if (text.isBlank()) throw EngineException("No hay texto que convertir a PDF.")
        val margin = 52f
        val pageWidth = 595f
        val pageHeight = 842f
        val fontSize = 11.5f
        val lineHeight = 16.5f
        val titleSize = 16f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = fontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            color = Color.BLACK
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.BLACK
        }

        val lines = wrapText(text.replace("\u00a0", " "), textPaint, pageWidth - margin * 2)
        onProgress(0.1f, "Maquetando texto")
        val document = PdfDocument()
        try {
            var page: PdfDocument.Page? = null
            var cursor = 0f
            var pageNumber = 0
            fun newPage() {
                page?.let { document.finishPage(it) }
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth.toInt(), pageHeight.toInt(), pageNumber).create())
                cursor = margin
            }
            newPage()
            page!!.canvas.drawText(title, margin, margin, titlePaint)
            cursor += titleSize * 1.9f

            for (line in lines) {
                if (cursor + lineHeight > pageHeight - margin) newPage()
                page!!.canvas.drawText(line, margin, cursor, textPaint)
                cursor += lineHeight
            }
            page?.let { document.finishPage(it) }
            onProgress(0.85f, "Escribiendo el PDF")
            FileOutputStream(outFile).use { document.writeTo(it) }
            return EngineOutcome(
                listOf(outFile),
                stats = mapOf("Páginas" to pageNumber.toString(), "Caracteres" to text.length.toString()),
            )
        } finally {
            document.close()
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        val paragraphs = text.split("\n")
        for (paragraph in paragraphs) {
            val trimmed = paragraph.trimEnd()
            if (trimmed.isEmpty()) {
                result += ""
                continue
            }
            val words = trimmed.split(" ")
            val current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                    current.clear()
                    current.append(candidate)
                } else {
                    result += current.toString()
                    current.clear()
                    current.append(word)
                }
            }
            if (current.isNotEmpty()) result += current.toString()
        }
        return result
    }

    // --------------------------------------------------------------- helpers

    private fun withRenderer(file: File, block: (PdfRenderer) -> Unit) {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(pfd)
            try {
                block(renderer)
            } finally {
                renderer.close()
            }
        } catch (error: IOException) {
            throw EngineException("No se pudo abrir «${file.name}» para renderizado. Puede estar cifrado o dañado.", error)
        } finally {
            pfd.close()
        }
    }

    private fun computeScale(pageWidth: Float, pageHeight: Float, dpi: Int): Float {
        val byDpi = dpi / 72f
        val byMemory = MAX_RENDER_DIMENSION / maxOf(pageWidth, pageHeight)
        return minOf(byDpi, byMemory).coerceIn(0.25f, 8f)
    }

    private fun toOpaque(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source.copy(Bitmap.Config.RGB_565, false)
        val target = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        return target
    }

    private fun reductionLabel(before: Long, after: Long): String {
        if (before <= 0 || after >= before) return "—"
        val percent = ((1.0 - after.toDouble() / before) * 100).toInt()
        return "$percent %"
    }
}