package com.pardocs.app.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Renders HTML with a real WebView engine and draws into a PdfDocument. This produces
 * faithful layout (CSS, tables, images) entirely offline. Returns false if it cannot
 * produce a document, so callers can fall back to a text-based PDF.
 */
object HtmlToPdf {
    suspend fun convert(
        context: Context,
        html: String,
        outFile: File,
        onProgress: ProgressListener = NoProgress,
    ): Boolean = withContext(Dispatchers.Main + NonCancellable) {
        val webView = WebView(context.applicationContext)
        try {
            webView.settings.javaScriptEnabled = false
            webView.settings.loadWithOverviewMode = true
            webView.settings.useWideViewPort = true
            webView.settings.builtInZoomControls = false
            webView.settings.displayZoomControls = false

            val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!loaded.isCompleted) loaded.complete(Unit)
                }
            }
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            withTimeoutOrNull(15_000) { loaded.await() } ?: return@withContext false

            onProgress(0.6f, "Renderizando el HTML")
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            val measuredWidth = webView.measuredWidth.coerceAtLeast(1)
            val measuredHeight = webView.measuredHeight.coerceAtLeast(1)
            webView.layout(0, 0, measuredWidth, measuredHeight)

            val pageWidth = 595f // A4 width @ 72dpi
            val pageHeight = 842f
            val scale = (pageWidth / measuredWidth.toFloat()).coerceIn(0.4f, 2.0f)

            val doc = PdfDocument()
            try {
                var offset = 0f
                var pageNo = 1
                while (offset < measuredHeight.toFloat()) {
                    val h = minOf(pageHeight, (measuredHeight - offset) * scale)
                    val info = PdfDocument.PageInfo.Builder(pageWidth.toInt(), h.toInt(), pageNo).create()
                    val page = doc.startPage(info)
                    val canvas: Canvas = page.canvas
                    canvas.save()
                    canvas.scale(scale, scale)
                    canvas.translate(0f, -offset)
                    webView.draw(canvas)
                    canvas.restore()
                    doc.finishPage(page)
                    offset += pageHeight / scale
                    pageNo++
                }
                onProgress(0.95f, "Guardando PDF")
                FileOutputStream(outFile).use { doc.writeTo(it) }
            } finally {
                doc.close()
            }
            outFile.exists() && outFile.length() > 0
        } catch (_: Throwable) {
            outFile.delete()
            false
        } finally {
            try {
                webView.destroy()
            } catch (_: Throwable) {
            }
        }
    }
}