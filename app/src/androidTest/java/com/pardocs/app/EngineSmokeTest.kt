package com.pardocs.app

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pardocs.app.core.WorkFile
import com.pardocs.app.engine.EngineException
import com.pardocs.app.engine.ExifEngine
import com.pardocs.app.engine.ImageEngine
import com.pardocs.app.engine.OfficeEngine
import com.pardocs.app.engine.PdfEngine
import com.pardocs.app.engine.codec.Argb
import com.pardocs.app.engine.codec.BmpCodec
import com.pardocs.app.engine.codec.TiffCodec
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On-device smoke tests for the real engines. Requires a connected device or
 * emulator (`connectedDebugAndroidTest`).
 */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {

    private lateinit var context: Context
    private lateinit var workDir: File
    private val outDir: File get() = File(workDir, "output").apply { mkdirs() }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workDir = File(context.cacheDir, "smoke").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun work(file: File, name: String = file.name) = WorkFile(file, name, null)

    private fun createTestPdf(text: String): File {
        val file = File(workDir, "origen_${text.length}.pdf")
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 18f)
                cs.newLineAtOffset(60f, 720f)
                cs.showText(text)
                cs.endText()
            }
            doc.save(file)
        }
        return file
    }

    private fun makeBitmap(): Bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) {
            setPixel(x, y, Argb.rgb((x * 5) % 256, (y * 8) % 256, (x + y) % 256))
        }
    }

    // --------------------------------------------------------------------- PDF

    @Test fun pdfMergeSplitProtectUnlockExtract() = runBlocking {
        PdfEngine.init(context)
        val first = createTestPdf("Hola mundo del PDF")
        val second = createTestPdf("Segundo documento")

        val merged = File(outDir, "merged.pdf")
        PdfEngine.merge(listOf(work(first), work(second)), merged)
        assertEquals(2, PdfEngine.pageCount(merged))

        val splitDir = File(outDir, "split").apply { mkdirs() }
        val split = PdfEngine.splitByRanges(work(first), splitDir, listOf("1-"))
        assertEquals(1, split.outputs.size)
        assertEquals(1, PdfEngine.pageCount(split.outputs.first()))

        val protected = File(outDir, "protegido.pdf")
        PdfEngine.protect(
            work(merged),
            protected,
            userPassword = "usuario",
            ownerPassword = "propietario",
            permissions = com.pardocs.app.engine.PdfPermissions(),
        )
        assertTrue(PdfEngine.metadata(protected)["Cifrado"] == "Sí" || runCatching { PdfEngine.pageCount(protected) }.isFailure)

        val unlocked = File(outDir, "desbloqueado.pdf")
        PdfEngine.unlock(work(protected), unlocked, "usuario")
        assertEquals(2, PdfEngine.pageCount(unlocked))

        val textFile = File(outDir, "texto.txt")
        PdfEngine.extractText(work(merged), textFile)
        assertTrue(textFile.readText(Charsets.UTF_8).contains("Hola mundo"))

        val wrongPasswordRejected = runCatching { PdfEngine.unlock(work(protected), File(outDir, "x.pdf"), "incorrecta") }
        assertTrue("Contraseña incorrecta debía fallar", wrongPasswordRejected.isFailure)
    }

    @Test fun pdfMergeRejectsSingleInput() = runBlocking {
        PdfEngine.init(context)
        val only = createTestPdf("Solo")
        val rejected = runCatching { PdfEngine.merge(listOf(work(only)), File(outDir, "nunca.pdf")) }
        assertTrue(rejected.exceptionOrNull() is EngineException)
    }

    // ---------------------------------------------------------------- codecs

    @Test fun bmpAndTiffRoundTripOnDevice() {
        val bitmap = makeBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val bmp = File(workDir, "prueba.bmp")
        FileOutputStream(bmp).use { BmpCodec.writePixels(bitmap.width, bitmap.height, pixels, it) }
        val bmpBack = BmpCodec.decodePixels(bmp.readBytes())
        assertEquals(bitmap.width, bmpBack.width)
        assertTrue(bmpBack.pixels.contentEquals(pixels))

        val tiff = File(workDir, "prueba.tiff")
        FileOutputStream(tiff).use {
            TiffCodec.writePixels(bitmap.width, bitmap.height, pixels, it, TiffCodec.Compression.DEFLATE)
        }
        val tiffBack = TiffCodec.decodePixels(tiff.readBytes())
        assertTrue(tiffBack.pixels.contentEquals(pixels))
    }

    @Test fun imageConvertThroughEngine() = runBlocking {
        val bitmap = makeBitmap()
        val png = File(workDir, "origen.png")
        FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val converted = ImageEngine.convert(
            listOf(work(png)),
            outDir,
            ImageEngine.ConvertOptions(format = "tiff", quality = 90),
        )
        assertEquals(1, converted.outputs.size)
        val decoded = ImageEngine.decode(converted.outputs.first())
        assertEquals(bitmap.width, decoded.width)
        assertEquals(bitmap.height, decoded.height)
    }

    // -------------------------------------------------------------------- EXIF

    @Test fun exifReadAndStrip() = runBlocking {
        val jpg = File(workDir, "foto.jpg")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        FileOutputStream(jpg).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val exif = ExifInterface(jpg.absolutePath)
        exif.setAttribute(ExifInterface.TAG_MAKE, "ParDocsCam")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Modelo X1")
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:01:01 12:00:00")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40, 26, 0")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "3, 42, 0")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
        exif.saveAttributes()

        val read = ExifEngine.read(jpg)
        assertTrue("Debían verse metadatos: ${read.map { it.key }}", read.any { it.key == ExifInterface.TAG_MAKE })

        val cleaned = ExifEngine.strip(
            listOf(work(jpg, "foto.jpg")),
            outDir,
            ExifEngine.StripOptions(removeAll = true),
        )
        assertEquals(1, cleaned.outputs.size)
        val cleanedExif = ExifInterface(cleaned.outputs.first().absolutePath)
        assertNull(cleanedExif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(cleanedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(cleanedExif.getAttribute(ExifInterface.TAG_DATETIME))
    }

    // ---------------------------------------------------------------- office

    @Test fun officeTextExtractionOnDevice() {
        val txt = File(workDir, "nota.txt")
        txt.writeText("Hola desde ParDocs\nSegunda línea", Charsets.UTF_8)
        assertEquals("Hola desde ParDocs\nSegunda línea", OfficeEngine.extractText(work(txt)))

        val docx = File(workDir, "documento.docx")
        ZipOutputStream(FileOutputStream(docx)).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Primer párrafo</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Segundo párrafo</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
        assertEquals("Primer párrafo\nSegundo párrafo", OfficeEngine.extractText(work(docx)))
    }
}