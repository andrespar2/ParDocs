package com.pardocs.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTest {

    @Test fun negativeBytesShowsDash() {
        assertEquals("-", Storage.formatSize(-1))
    }

    @Test fun bytesUnderOneKiloByte() {
        assertEquals("0 B", Storage.formatSize(0))
        assertEquals("512 B", Storage.formatSize(512))
        assertEquals("1023 B", Storage.formatSize(1023))
    }

    @Test fun kiloBytesWithDecimalForSmallValues() {
        assertEquals("1.0 KB", Storage.formatSize(1024))
        assertEquals("1.5 KB", Storage.formatSize(1536))
        assertEquals("9.9 KB", Storage.formatSize(10 * 1024 - 102))
    }

    @Test fun wholeUnitsOnceBigEnough() {
        assertEquals("10 KB", Storage.formatSize(10 * 1024))
        assertEquals("1.0 MB", Storage.formatSize(1024 * 1024))
        assertEquals("12 MB", Storage.formatSize(12 * 1024 * 1024))
    }

    @Test fun largerUnits() {
        assertEquals("1.0 GB", Storage.formatSize(1024L * 1024 * 1024))
        assertEquals("2.5 GB", Storage.formatSize(2560L * 1024 * 1024))
        assertEquals("1.0 TB", Storage.formatSize(1024L * 1024 * 1024 * 1024))
    }
}