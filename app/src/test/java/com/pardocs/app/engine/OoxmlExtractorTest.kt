package com.pardocs.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OoxmlExtractorTest {

    private fun writeZip(suffix: String, vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("ooxml", suffix)
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test fun docxExtractsParagraphText() {
        val file = writeZip(
            ".docx",
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Hola</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Mundo</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent(),
        )
        try {
            assertEquals("Hola\nMundo", OoxmlExtractor.extract(file))
        } finally {
            file.delete()
        }
    }

    @Test fun xlsxResolvesSharedStringsAndTabsCells() {
        val file = writeZip(
            ".xlsx",
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>Hola</t></si>
                  <si><t>Mundo</t></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="s"><v>0</v></c>
                      <c r="B1" t="s"><v>1</v></c>
                    </row>
                  </sheetData>
                </worksheet>
            """.trimIndent(),
        )
        try {
            val text = OoxmlExtractor.extract(file)
            assertTrue("Falta la hoja cabecera en: $text", text.contains("Hoja 1"))
            assertTrue("Faltan las celdas en: $text", text.contains("Hola\tMundo"))
        } finally {
            file.delete()
        }
    }

    @Test fun pptxExtractsSlideTextInOrder() {
        val file = writeZip(
            ".pptx",
            "ppt/slides/slide1.xml" to """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree>
                    <p:sp><p:txBody><a:p><a:r><a:t>Primera</a:t></a:r></a:p></p:txBody></p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
            """.trimIndent(),
            "ppt/slides/slide2.xml" to """
                <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                  <p:cSld><p:spTree>
                    <p:sp><p:txBody><a:p><a:r><a:t>Segunda</a:t></a:r></a:p></p:txBody></p:sp>
                  </p:spTree></p:cSld>
                </p:sld>
            """.trimIndent(),
        )
        try {
            val text = OoxmlExtractor.extract(file)
            val first = text.indexOf("Primera")
            val second = text.indexOf("Segunda")
            assertTrue("Falta Primera en: $text", first >= 0)
            assertTrue("Falta Segunda en: $text", second >= 0)
            assertTrue("Las diapositivas deben ir en orden", first < second)
        } finally {
            file.delete()
        }
    }

    @Test fun nonOfficeFileIsRejected() {
        val file = File.createTempFile("ooxml", ".zip")
        try {
            val rejected = runCatching { OoxmlExtractor.extract(file) }
            assertTrue(rejected.isFailure)
        } finally {
            file.delete()
        }
    }
}