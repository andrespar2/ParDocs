package com.pardocs.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEngineTest {

    // ---------------------------------------------------------------- decoding

    @Test fun utf8WithBomIsHonoured() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "Hola mundo".toByteArray(Charsets.UTF_8)
        assertEquals("Hola mundo", TextEngine.decodeText(bytes))
    }

    @Test fun utf16BomVariants() {
        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hola".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hola", TextEngine.decodeText(utf16le))
        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "Hola".toByteArray(Charsets.UTF_16BE)
        assertEquals("Hola", TextEngine.decodeText(utf16be))
    }

    @Test fun plainUtf8FallsBackToIso8859ForInvalidBytes() {
        assertEquals("Hola", TextEngine.decodeText("Hola".toByteArray(Charsets.UTF_8)))
        // 0xE1 is invalid UTF-8 on its own -> Latin-1 fallback gives 'á'.
        assertEquals("á", TextEngine.decodeText(byteArrayOf(0xE1.toByte())))
    }

    // ------------------------------------------------------------------- HTML

    @Test fun htmlStripsScriptsStylesAndTags() {
        val html = """
            <html><head><title>t</title><style>.x { color: red }</style></head>
            <body><script>alert('x');</script><p>Hola</p><p>Mundo</p><br/>Fin</body></html>
        """.trimIndent()
        val text = TextEngine.stripHtml(html)
        assertEquals("Hola\nMundo\nFin", text)
    }

    @Test fun htmlDecodesNamedAndNumericEntities() {
        val html = "Hola &aacute;migo &amp; yo &#65;&#x42; &nbsp;fin"
        assertEquals("Hola ámigo & yo AB  fin", TextEngine.stripHtml(html))
    }

    // --------------------------------------------------------------------- RTF

    @Test fun rtfParagraphsBecomeNewlines() {
        val rtf = """{\rtf1\ansi Hola\par Mundo\par Fin}"""
        assertEquals("Hola\nMundo\nFin", TextEngine.rtfToText(rtf))
    }

    @Test fun rtfUnicodeAndHexEscapes() {
        // \u225 = á (with space delimiter), \'e1 = á in the 0x80..0x9F table below it is plain toChar.
        val rtf = """{\rtf1\ansi Caf\u225 \'e9}"""
        assertEquals("Cafáé", TextEngine.rtfToText(rtf))
    }

    @Test fun rtfSkipsDestinations() {
        val rtf = """{\rtf1{\*\generator LibreOffice 7.2}Hola}"""
        assertEquals("Hola", TextEngine.rtfToText(rtf))
    }

    @Test fun fileBasedExtraction() {
        val file = java.io.File.createTempFile("prueba", ".txt")
        file.writeText("Contenido de prueba\nSegunda línea", Charsets.UTF_8)
        try {
            assertEquals("Contenido de prueba\nSegunda línea", TextEngine.plainText(file))
        } finally {
            file.delete()
        }
    }
}