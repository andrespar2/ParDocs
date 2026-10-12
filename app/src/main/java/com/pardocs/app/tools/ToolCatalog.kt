package com.pardocs.app.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhotoSizeSelectSmall
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tag
import com.pardocs.app.core.Formats
import com.pardocs.app.engine.EngineException
import com.pardocs.app.engine.ImageEngine
import com.pardocs.app.engine.OfficeEngine
import com.pardocs.app.engine.PdfEngine
import com.pardocs.app.engine.PdfRender
import com.pardocs.app.engine.UnsupportedFormatException

/**
 * The full list of ParDocs tools. Every tool has a real local implementation —
 * there are no simulated buttons. Tools whose operation is impossible on-device
 * surface an explicit, honest message instead.
 */
object ToolCatalog {

    val tools: List<ToolSpec> = listOf(
        // ------------------------------------------------------------------ PDF
        ToolSpec(
            id = "merge",
            title = "Unir PDF",
            description = "Combina varios PDF en uno solo, en el orden elegido.",
            icon = Icons.Outlined.CallMerge,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            minFiles = 2,
            maxFiles = 30,
            outputBase = "unido",
        ) { ctx ->
            val output = uniqueOut("unido", "pdf", ctx)
            PdfEngine.merge(ctx.inputs, output, ctx.onProgress)
        },
        ToolSpec(
            id = "split",
            title = "Dividir PDF",
            description = "Extrae rangos de páginas o trocea el documento en partes.",
            icon = Icons.Outlined.CallSplit,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "paginas",
            options = listOf(
                ToolOption(
                    id = "mode", label = "Modo", kind = OptionKind.CHOICE,
                    default = "ranges",
                    choices = listOf("ranges" to "Rangos personalizados", "every" to "Cada N páginas"),
                ),
                ToolOption(
                    id = "ranges", label = "Páginas", kind = OptionKind.TEXT,
                    default = "1-3,5",
                    help = "Ejemplo: 1-3, 5, 8- (páginas 1 a 3, la 5 y de 8 al final).",
                    visibleWhen = { it["mode"] == "ranges" },
                ),
                ToolOption(
                    id = "every", label = "Páginas por parte", kind = OptionKind.SLIDER,
                    min = 1, max = 20, step = 1, default = 2, unit = " pág.",
                    visibleWhen = { it["mode"] == "every" },
                ),
            ),
        ) { ctx ->
            val file = singleInput(ctx)
            PdfEngine.requirePdf(file)
            when (ctx.str("mode", "ranges")) {
                "every" -> PdfEngine.splitEvery(file, ctx.outDir, ctx.int("every", 2), ctx.onProgress)
                else -> PdfEngine.splitByRanges(
                    file, ctx.outDir,
                    ctx.str("ranges", "1,2").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    ctx.onProgress,
                )
            }
        },
        ToolSpec(
            id = "compress",
            title = "Comprimir PDF",
            description = "Reduce el tamaño rasterizando las páginas a menor resolución. Ideal para escaneos.",
            icon = Icons.Outlined.Compress,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "comprimido",
            options = listOf(
                ToolOption(
                    id = "dpi", label = "Resolución", kind = OptionKind.SLIDER,
                    min = 72, max = 300, step = 1, default = 150, unit = " dpi",
                ),
                ToolOption(
                    id = "quality", label = "Calidad JPEG", kind = OptionKind.SLIDER,
                    min = 30, max = 90, step = 5, default = 60, unit = " %",
                ),
            ),
        ) { ctx ->
            val file = singleInput(ctx)
            PdfEngine.requirePdf(file)
            val output = uniqueOut("comprimido", "pdf", ctx)
            PdfRender.compressRasterized(file, output, ctx.int("dpi", 150), ctx.int("quality", 60), ctx.onProgress)
        },
        ToolSpec(
            id = "organize",
            title = "Organizar páginas",
            description = "Ordena, rota y elimina páginas con vista previa.",
            icon = Icons.Outlined.Reorder,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "organizado",
            customScreen = "organizer",
        ) { throw EngineException("Flujo personalizado") },
        ToolSpec(
            id = "protect",
            title = "Proteger con contraseña",
            description = "Cifra el PDF y limita impresión, copia y edición.",
            icon = Icons.Outlined.Lock,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "protegido",
            options = listOf(
                ToolOption(id = "user", label = "Contraseña de apertura", kind = OptionKind.TEXT, default = "", secret = true,
                    help = "Quién la necesita para abrir el archivo."),
                ToolOption(id = "owner", label = "Contraseña de permisos", kind = OptionKind.TEXT, default = "", secret = true,
                    help = "Para cambiar los permisos. Si se deja vacía se usa la de apertura."),
                ToolOption(id = "print", label = "Permitir imprimir", kind = OptionKind.TOGGLE, default = true),
                ToolOption(id = "copy", label = "Permitir copiar texto", kind = OptionKind.TOGGLE, default = false),
                ToolOption(id = "modify", label = "Permitir editar", kind = OptionKind.TOGGLE, default = false),
            ),
        ) { ctx ->
            val file = singleInput(ctx)
            PdfEngine.requirePdf(file)
            val output = uniqueOut("protegido", "pdf", ctx)
            PdfEngine.protect(
                file, output,
                ctx.str("user", ""), ctx.str("owner", ""),
                com.pardocs.app.engine.PdfPermissions(
                    allowPrinting = ctx.bool("print", true),
                    allowCopy = ctx.bool("copy", false),
                    allowModify = ctx.bool("modify", false),
                    allowAnnotations = ctx.bool("modify", false),
                ),
                ctx.onProgress,
            )
        },
        ToolSpec(
            id = "unlock",
            title = "Desbloquear PDF",
            description = "Elimina el cifrado usando la contraseña correcta.",
            icon = Icons.Outlined.LockOpen,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "desbloqueado",
            options = listOf(
                ToolOption(id = "password", label = "Contraseña", kind = OptionKind.TEXT, default = "", secret = true),
            ),
        ) { ctx ->
            val file = singleInput(ctx)
            PdfEngine.requirePdf(file)
            val output = uniqueOut("desbloqueado", "pdf", ctx)
            PdfEngine.unlock(file, output, ctx.str("password", ""), ctx.onProgress)
        },
        ToolSpec(
            id = "pdfToImages",
            title = "PDF a imágenes",
            description = "Convierte cada página en una imagen JPG o PNG.",
            icon = Icons.Outlined.Image,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "pagina",
            options = listOf(
                ToolOption(id = "format", label = "Formato", kind = OptionKind.CHOICE,
                    default = "jpg", choices = listOf("jpg" to "JPG", "png" to "PNG")),
                ToolOption(id = "dpi", label = "Calidad", kind = OptionKind.SLIDER,
                    min = 72, max = 300, step = 1, default = 150, unit = " dpi"),
            ),
        ) { ctx ->
            val file = singleInput(ctx)
            PdfEngine.requirePdf(file)
            PdfRender.toImages(
                file, ctx.outDir,
                PdfRender.RenderOptions(
                    format = ctx.str("format", "jpg"),
                    dpi = ctx.int("dpi", 150),
                    quality = 92,
                ),
                ctx.onProgress,
            )
        },
        ToolSpec(
            id = "pdfToText",
            title = "PDF a texto",
            description = "Extrae el texto seleccionable del PDF a un archivo TXT.",
            icon = Icons.Outlined.Subject,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "texto",
        ) { ctx ->
            val file = singleInput(ctx)
            PdfEngine.requirePdf(file)
            val output = uniqueOut("texto_extraido", "txt", ctx)
            PdfEngine.extractText(file, output, ctx.onProgress)
        },
        ToolSpec(
            id = "extractPages",
            title = "Extraer páginas",
            description = "Guarda solo las páginas que elijas en un PDF nuevo.",
            icon = Icons.Outlined.ContentCut,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "extraido",
            options = listOf(
                ToolOption(id = "pages", label = "Páginas", kind = OptionKind.TEXT, default = "1-3",
                    help = "Ejemplo: 1-3, 5, 8- (páginas 1 a 3, la 5 y de 8 al final)."),
            ),
        ) { ctx ->
            val file = singleInput(ctx)
            PdfEngine.requirePdf(file)
            val output = uniqueOut("paginas_extraidas", "pdf", ctx)
            PdfEngine.extractPages(file, output, ctx.str("pages", "1,2"), ctx.onProgress)
        },
        ToolSpec(
            id = "stripPdfMetadata",
            title = "Limpiar metadatos PDF",
            description = "Elimina autor, fechas, software y otros datos del archivo.",
            icon = Icons.Outlined.Tag,
            category = ToolCategory.PDF,
            mimeTypes = PdfToolMime.pdf,
            outputBase = "limpio",
        ) { ctx ->
            val file = singleInput(ctx)
            PdfEngine.requirePdf(file)
            val output = uniqueOut("pdf_sin_metadatos", "pdf", ctx)
            PdfEngine.stripMetadata(file, output, ctx.onProgress)
        },

        // ------------------------------------------------------------- IMAGES
        ToolSpec(
            id = "convertImage",
            title = "Convertir formato",
            description = "JPG, PNG, WEBP, BMP, TIFF y más, en ambas direcciones.",
            icon = Icons.Outlined.SwapHoriz,
            category = ToolCategory.IMAGE,
            mimeTypes = PdfToolMime.image,
            maxFiles = 50,
            outputBase = "convertida",
            options = listOf(
                ToolOption(id = "format", label = "Formato de salida", kind = OptionKind.CHOICE, default = "jpg",
                    choices = listOf(
                        "jpg" to "JPG", "png" to "PNG", "webp" to "WEBP",
                        "bmp" to "BMP", "tiff" to "TIFF",
                    )),
                ToolOption(id = "quality", label = "Calidad", kind = OptionKind.SLIDER,
                    min = 30, max = 100, step = 5, default = 90, unit = " %",
                    visibleWhen = { it["format"] == "jpg" || it["format"] == "webp" },
                    help = "Los formatos PNG, BMP y TIFF son sin pérdida."),
            ),
        ) { ctx ->
            ImageEngine.convert(
                ctx.inputs, ctx.outDir,
                ImageEngine.ConvertOptions(
                    format = ctx.str("format", "jpg"),
                    quality = ctx.int("quality", 90),
                ),
                ctx.onProgress,
            )
        },
        ToolSpec(
            id = "compressImage",
            title = "Comprimir imágenes",
            description = "Reduce dimensiones y tamaño para web o mensajería.",
            icon = Icons.Outlined.PhotoSizeSelectSmall,
            category = ToolCategory.IMAGE,
            mimeTypes = PdfToolMime.image,
            maxFiles = 50,
            outputBase = "optimizada",
            options = listOf(
                ToolOption(id = "maxDim", label = "Lado mayor máximo", kind = OptionKind.SLIDER,
                    min = 256, max = 4096, step = 64, default = 1920, unit = " px"),
                ToolOption(id = "quality", label = "Calidad", kind = OptionKind.SLIDER,
                    min = 30, max = 95, step = 5, default = 75, unit = " %"),
                ToolOption(id = "format", label = "Formato", kind = OptionKind.CHOICE,
                    default = "jpg", choices = listOf("jpg" to "JPG", "webp" to "WEBP")),
            ),
        ) { ctx ->
            ImageEngine.optimize(
                ctx.inputs, ctx.outDir,
                ImageEngine.ConvertOptions(
                    format = ctx.str("format", "jpg"),
                    quality = ctx.int("quality", 75),
                    maxDimension = ctx.int("maxDim", 1920),
                ),
                ctx.onProgress,
            )
        },
        ToolSpec(
            id = "imagesToPdf",
            title = "Imágenes a PDF",
            description = "Agrupa fotos o escaneos en un documento PDF.",
            icon = Icons.Outlined.PictureAsPdf,
            category = ToolCategory.IMAGE,
            mimeTypes = PdfToolMime.image,
            minFiles = 1,
            maxFiles = 100,
            outputBase = "imagenes",
            options = listOf(
                ToolOption(id = "pageSize", label = "Tamaño de página", kind = OptionKind.CHOICE,
                    default = "A4",
                    choices = listOf("A4" to "A4 con márgenes", "image" to "Tamaño de la imagen")),
                ToolOption(id = "margin", label = "Margen", kind = OptionKind.SLIDER,
                    min = 0, max = 96, step = 4, default = 40, unit = " pt",
                    visibleWhen = { it["pageSize"] == "A4" }),
            ),
        ) { ctx ->
            val output = uniqueOut("imagenes_a_pdf", "pdf", ctx)
            PdfRender.imagesToPdf(
                ctx.inputs, output,
                pageSizeMode = ctx.str("pageSize", "A4"),
                marginPt = ctx.int("margin", 40),
                ctx.onProgress,
            )
        },
        ToolSpec(
            id = "metadata",
            title = "Metadatos EXIF",
            description = "Consulta y borra GPS, cámara, fechas y más, de una en una o en lote.",
            icon = Icons.Outlined.Info,
            category = ToolCategory.IMAGE,
            mimeTypes = PdfToolMime.image,
            maxFiles = 50,
            outputBase = "limpia",
            customScreen = "metadata",
        ) { throw EngineException("Flujo personalizado") },

        // ---------------------------------------------------------- DOCUMENTS
        ToolSpec(
            id = "docsToText",
            title = "Documentos a texto",
            description = "Extrae el texto de Word, Excel, PowerPoint, TXT, HTML o RTF.",
            icon = Icons.Outlined.Description,
            category = ToolCategory.DOCUMENT,
            mimeTypes = PdfToolMime.documents,
            outputBase = "documento",
        ) { ctx ->
            val file = singleInput(ctx)
            val output = uniqueOut("texto_documento", "txt", ctx)
            OfficeEngine.toPlainText(file, output, ctx.onProgress)
        },
        ToolSpec(
            id = "docsToPdf",
            title = "Documentos a PDF",
            description = "Convierte documentos y textos a PDF (HTML se renderiza con su formato).",
            icon = Icons.Outlined.Article,
            category = ToolCategory.DOCUMENT,
            mimeTypes = PdfToolMime.documents,
            outputBase = "documento",
            options = listOf(
                ToolOption(id = "renderHtml", label = "Renderizar HTML con su formato", kind = OptionKind.TOGGLE, default = true,
                    help = "Usa el motor WebView del dispositivo. Si falla, se convierte el texto."),
            ),
        ) { ctx ->
            val file = singleInput(ctx)
            val output = uniqueOut("documento_a_pdf", "pdf", ctx)
            OfficeEngine.toPdf(ctx.context, file, output, ctx.bool("renderHtml", true), ctx.onProgress)
        },
    )

    fun byId(id: String): ToolSpec? = tools.firstOrNull { it.id == id }

    fun byCategory(category: ToolCategory): List<ToolSpec> = tools.filter { it.category == category }

    private fun singleInput(ctx: ToolContext) =
        ctx.inputs.firstOrNull()
            ?: throw EngineException("Selecciona un archivo primero.")
}