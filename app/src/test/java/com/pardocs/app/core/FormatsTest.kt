package com.pardocs.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {

    @Test fun extensionIsLowercasedAndStripped() {
        assertEquals("docx", Formats.extensionOf("Informe.FINAL.DOCX"))
        assertEquals("", Formats.extensionOf("sin-extension"))
    }

    @Test fun categoryDetection() {
        assertEquals(Formats.Category.PDF, Formats.categoryOf("pdf"))
        assertEquals(Formats.Category.IMAGE, Formats.categoryOf("jpg"))
        assertEquals(Formats.Category.IMAGE, Formats.categoryOf("bmp"))
        assertEquals(Formats.Category.DOCUMENT, Formats.categoryOf("docx"))
        assertEquals(Formats.Category.DOCUMENT, Formats.categoryOf("txt"))
        assertEquals(Formats.Category.DOCUMENT, Formats.categoryOf("html"))
        assertEquals(Formats.Category.DOCUMENT, Formats.categoryOf("rtf"))
        assertEquals(Formats.Category.OTHER, Formats.categoryOf("exe"))
    }

    @Test fun mimeTypeMapping() {
        assertEquals("application/pdf", Formats.mimeOf("pdf"))
        assertEquals("image/tiff", Formats.mimeOf("tiff"))
        assertEquals("text/csv", Formats.mimeOf("csv"))
        assertEquals("application/rtf", Formats.mimeOf("rtf"))
        assertEquals("application/octet-stream", Formats.mimeOf("nope"))
    }

    @Test fun imageEncodingSupportIsHonest() {
        assertTrue(Formats.supportsImageEncoding("png"))
        assertTrue(Formats.supportsImageEncoding("webp"))
        assertTrue(Formats.supportsImageEncoding("bmp"))
        assertFalse(Formats.supportsImageEncoding("heic"))
        assertFalse(Formats.supportsImageEncoding("gif"))
        assertFalse(Formats.supportsImageEncoding("avif"))
    }

    @Test fun unsupportedTargetsExplainThemselves() {
        assertNotNull(Formats.describeUnsupportedImageTarget("heic"))
        assertNotNull(Formats.describeUnsupportedImageTarget("avif"))
        assertNull(Formats.describeUnsupportedImageTarget("jpg"))
    }

    @Test fun legacyOfficeFormatsAreDeclaredUnsupported() {
        assertNotNull(Formats.describeUnsupportedDocument("doc"))
        assertNotNull(Formats.describeUnsupportedDocument("xls"))
        assertNull(Formats.describeUnsupportedDocument("docx"))
    }
}