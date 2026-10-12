package com.pardocs.app.engine

import android.content.Context
import com.pardocs.app.core.Formats
import com.pardocs.app.core.PageRange
import com.pardocs.app.core.Storage
import com.pardocs.app.core.WorkFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Rotation / removal instructions for the page organiser. */
data class OrganizeOp(val sourceIndex: Int, val rotationDelta: Int)

data class PdfPermissions(
    val allowPrinting: Boolean = true,
    val allowCopy: Boolean = false,
    val allowModify: Boolean = false,
    val allowAnnotations: Boolean = false,
)

/**
 * Structural PDF operations. Backed by PDFBox-Android (Apache-2.0, pure Java),
 * which keeps the APK ABI-independent and fully offline.
 */
object PdfEngine {

    fun init(context: Context) {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    // ---------------------------------------------------------------- inspection

    fun pageCount(file: File): Int = withDocument(file) { it.numberOfPages }

    fun metadata(file: File): Map<String, String> = try {
        readMetadata(file)
    } catch (passwordError: EngineException) {
        // A user-password-protected PDF cannot be opened without the password;
        // still report what we can instead of failing outright.
        if (!isEncrypted(file)) throw passwordError
        buildMap {
            put("Páginas", "—")
            put("Tamaño del archivo", Storage.formatSize(file.length()))
            put("Cifrado", "Sí")
            put("Nota", "El documento está protegido: usa «Desbloquear PDF» para ver el resto.")
        }
    }

    private fun readMetadata(file: File): Map<String, String> = withDocument(file) { document ->
        val info = document.documentInformation
        buildMap {
            put("Páginas", document.numberOfPages.toString())
            put("Tamaño del archivo", Storage.formatSize(file.length()))
            info?.title?.let { put("Título", it) }
            info?.author?.let { put("Autor", it) }
            info?.subject?.let { put("Asunto", it) }
            info?.keywords?.let { put("Palabras clave", it) }
            info?.creator?.let { put("Creador", it) }
            info?.producer?.let { put("Productor", it) }
            info?.creationDate?.let { put("Fecha de creación", it.time.toString()) }
            info?.modificationDate?.let { put("Fecha de modificación", it.time.toString()) }
            put("Cifrado", if (document.isEncrypted) "Sí" else "No")
        }
    }

    /** True when the file needs a password to open (or uses an owner-only policy). */
    private fun isEncrypted(file: File): Boolean = try {
        PDDocument.load(file, "").use { it.isEncrypted }
    } catch (_: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
        true
    } catch (_: Exception) {
        false
    }

    private fun <T> withDocument(file: File, block: (PDDocument) -> T): T {
        val document = open(file)
        return document.use(block)
    }

    private fun open(file: File, password: String = ""): PDDocument = try {
        PDDocument.load(file, password)
    } catch (error: Exception) {
        throw EngineException(describeLoadFailure(file, error), error)
    }

    private fun describeLoadFailure(file: File, error: Exception): String {
        val name = file.name
        val message = error.message.orEmpty()
        return when {
            message.contains("password", ignoreCase = true) || message.contains("encrypt", ignoreCase = true) ->
                "«$name» está protegido con contraseña. Usa primero la herramienta Desbloquear PDF."
            else -> "No se pudo abrir «$name». Puede estar dañado o no ser un PDF válido. ($message)"
        }
    }

    // -------------------------------------------------------------------- merge

    suspend fun merge(
        files: List<WorkFile>,
        outFile: File,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        if (files.size < 2) throw EngineException("Selecciona al menos dos PDF para unir.")
        val warnings = mutableListOf<String>()
        var totalPages = 0
        PDDocument().use { output ->
            files.forEachIndexed { index, workFile ->
                checkCancelled()
                onProgress(index.toFloat() / files.size, "Añadiendo ${workFile.displayName}")
                runCatching {
                    open(workFile.file).use { source ->
                        for (page in 0 until source.numberOfPages) {
                            output.importPage(source.getPage(page))
                        }
                        totalPages += source.numberOfPages
                    }
                }.onFailure { error ->
                    warnings += "Se omitió «${workFile.displayName}»: ${error.message}"
                }
            }
            if (totalPages == 0) throw EngineException("Ninguno de los archivos pudo leerse como PDF.")
            onProgress(0.95f, "Escribiendo el PDF unido")
            output.save(outFile)
        }
        return EngineOutcome(
            outputs = listOf(outFile),
            warnings = warnings,
            stats = mapOf("Páginas resultantes" to totalPages.toString()),
        )
    }

    // -------------------------------------------------------------------- split

    /** Splits each given range into its own document. */
    suspend fun splitByRanges(
        file: WorkFile,
        outDir: File,
        ranges: List<String>,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        val pageCount = pageCount(file.file)
        val outputs = mutableListOf<File>()
        open(file.file).use { source ->
            ranges.forEachIndexed { rangeIndex, expression ->
                checkCancelled()
                val pages = when (val parsed = PageRange.parse(expression, pageCount)) {
                    is PageRange.Result.Ok -> parsed.pages
                    is PageRange.Result.Error -> throw EngineException(parsed.message)
                }
                onProgress(rangeIndex.toFloat() / ranges.size, "Extrayendo páginas $expression")
                val label = "paginas_${expression.replace(Regex("""[^0-9a-zA-Z]+"""), "-").trim('-')}"
                val target = Storage.uniqueFile(outDir, "${file.displayName.substringBeforeLast('.')}_$label", "pdf")
                PDDocument().use { part ->
                    pages.forEach { part.importPage(source.getPage(it)) }
                    part.save(target)
                }
                outputs += target
            }
        }
        return EngineOutcome(outputs, stats = mapOf("Documentos generados" to outputs.size.toString()))
    }

    /** Splits a document every N pages. */
    suspend fun splitEvery(
        file: WorkFile,
        outDir: File,
        pagesPerFile: Int,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        if (pagesPerFile < 1) throw EngineException("El número de páginas por archivo debe ser al menos 1.")
        val pageCount = pageCount(file.file)
        val outputs = mutableListOf<File>()
        val base = file.displayName.substringBeforeLast('.')
        open(file.file).use { source ->
            var index = 0
            var part = 1
            while (index < pageCount) {
                checkCancelled()
                onProgress(index.toFloat() / pageCount, "Generando parte $part")
                val end = minOf(index + pagesPerFile, pageCount)
                val target = Storage.uniqueFile(outDir, "${base}_parte$part", "pdf")
                PDDocument().use { chunk ->
                    for (page in index until end) chunk.importPage(source.getPage(page))
                    chunk.save(target)
                }
                outputs += target
                index = end
                part++
            }
        }
        return EngineOutcome(outputs, stats = mapOf("Documentos generados" to outputs.size.toString()))
    }

    // ---------------------------------------------------------------- organiser

    suspend fun organize(
        file: WorkFile,
        outFile: File,
        ops: List<OrganizeOp>,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        if (ops.isEmpty()) throw EngineException("No queda ninguna página que guardar.")
        val pageCount = pageCount(file.file)
        open(file.file).use { source ->
            PDDocument().use { target ->
                ops.forEachIndexed { order, op ->
                    checkCancelled()
                    if (op.sourceIndex !in 0 until pageCount) {
                        throw EngineException("Página ${op.sourceIndex + 1} fuera de rango.")
                    }
                    onProgress(order.toFloat() / ops.size, "Organizando página ${order + 1} de ${ops.size}")
                    val page = target.importPage(source.getPage(op.sourceIndex))
                    page.rotation = ((page.rotation + op.rotationDelta) % 360 + 360) % 360
                }
                onProgress(0.95f, "Escribiendo el PDF")
                target.save(outFile)
            }
        }
        return EngineOutcome(listOf(outFile), stats = mapOf("Páginas" to ops.size.toString()))
    }

    // ------------------------------------------------------------------ protect

    suspend fun protect(
        file: WorkFile,
        outFile: File,
        userPassword: String,
        ownerPassword: String,
        permissions: PdfPermissions,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        if (userPassword.isEmpty() && ownerPassword.isEmpty()) {
            throw EngineException("Indica al menos una contraseña.")
        }
        onProgress(0.2f, "Leyendo el documento")
        open(file.file).use { document ->
            val access = AccessPermission().apply {
                setCanPrint(permissions.allowPrinting)
                setCanExtractContent(permissions.allowCopy)
                setCanModify(permissions.allowModify)
                setCanModifyAnnotations(permissions.allowAnnotations)
                setCanFillInForm(permissions.allowModify)
                setCanExtractForAccessibility(true)
                setCanAssembleDocument(permissions.allowModify)
                setCanPrintDegraded(permissions.allowPrinting)
            }
            val policy = StandardProtectionPolicy(
                ownerPassword.ifEmpty { userPassword },
                userPassword,
                access,
            ).apply {
                encryptionKeyLength = 128
                isPreferAES = true
            }
            onProgress(0.6f, "Aplicando cifrado AES-128")
            document.protect(policy)
            document.save(outFile)
        }
        return EngineOutcome(
            listOf(outFile),
            stats = mapOf("Cifrado" to "AES-128", "Tamaño" to Storage.formatSize(outFile.length())),
        )
    }

    suspend fun unlock(
        file: WorkFile,
        outFile: File,
        password: String,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        onProgress(0.2f, "Descifrando el documento")
        val document = try {
            PDDocument.load(file.file, password)
        } catch (error: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw EngineException("Contraseña incorrecta para «${file.displayName}».")
        } catch (error: Exception) {
            throw EngineException("No se pudo abrir «${file.displayName}»: ${error.message}")
        }
        val wasEncrypted = document.isEncrypted
        document.use {
            it.isAllSecurityToBeRemoved = true
            onProgress(0.7f, "Guardando copia sin protección")
            it.save(outFile)
        }
        if (!wasEncrypted) {
            return EngineOutcome(
                listOf(outFile),
                warnings = listOf("«${file.displayName}» no estaba cifrado: se guardó una copia sin cambios de protección."),
                stats = mapOf("Protección" to "no aplicable"),
            )
        }
        return EngineOutcome(listOf(outFile), stats = mapOf("Protección" to "eliminada"))
    }

    // --------------------------------------------------------------------- misc

    suspend fun extractPages(
        file: WorkFile,
        outFile: File,
        expression: String,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        val pageCount = pageCount(file.file)
        val pages = when (val parsed = PageRange.parse(expression, pageCount)) {
            is PageRange.Result.Ok -> parsed.pages
            is PageRange.Result.Error -> throw EngineException(parsed.message)
        }
        open(file.file).use { source ->
            onProgress(0.4f, "Extrayendo ${pages.size} página(s)")
            PDDocument().use { target ->
                pages.forEach { target.importPage(source.getPage(it)) }
                target.save(outFile)
            }
        }
        return EngineOutcome(listOf(outFile), stats = mapOf("Páginas extraídas" to pages.size.toString()))
    }

    suspend fun extractText(
        file: WorkFile,
        outFile: File,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        onProgress(0.3f, "Extrayendo texto")
        val text = open(file.file).use { document ->
            PDFTextStripper().apply { sortByPosition = true }.getText(document)
        }
        if (text.isBlank()) {
            throw EngineException("El PDF no contiene texto seleccionable (probablemente sea un documento escaneado). Prueba Documentos → texto sobre el original, o conviértelo a imágenes y aplica OCR externo.")
        }
        onProgress(0.85f, "Guardando el texto")
        outFile.writeText(text, Charsets.UTF_8)
        return EngineOutcome(
            listOf(outFile),
            stats = mapOf("Caracteres" to text.length.toString(), "Palabras" to text.split(Regex("\\s+")).count { it.isNotBlank() }.toString()),
        )
    }

    suspend fun stripMetadata(
        file: WorkFile,
        outFile: File,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        onProgress(0.3f, "Eliminando metadatos del PDF")
        open(file.file).use { document ->
            document.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
            document.documentCatalog.metadata = null
            onProgress(0.8f, "Guardando el PDF")
            document.save(outFile)
        }
        return EngineOutcome(listOf(outFile), stats = mapOf("Metadatos" to "eliminados"))
    }

    /** Adds the extension check used by every PDF entry point. */
    fun requirePdf(file: WorkFile) {
        if (file.extension != Formats.PDF) {
            throw UnsupportedFormatException("«${file.displayName}» no es un PDF.")
        }
    }
}
