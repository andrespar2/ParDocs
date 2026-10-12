package com.pardocs.app.engine.codec

import com.pardocs.app.engine.UnsupportedFormatException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TiffCodecTest {

    private fun roundTrip(pixels: IntArray, width: Int, height: Int, compression: TiffCodec.Compression): PixelImage {
        val out = ByteArrayOutputStream()
        TiffCodec.writePixels(width, height, pixels, out, compression)
        val bytes = out.toByteArray()
        // Writer emits a little-endian TIFF.
        assertEquals('I'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        val image = TiffCodec.decodePixels(bytes)
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        return image
    }

    @Test fun roundTripUncompressed() {
        val width = 4
        val height = 3
        val pixels = IntArray(width * height) {
            Argb.rgb((it * 40) % 256, (it * 60) % 256, (it * 80) % 256)
        }
        assertArrayEquals(pixels, roundTrip(pixels, width, height, TiffCodec.Compression.NONE).pixels)
    }

    @Test fun roundTripDeflate() {
        val width = 5
        val height = 4
        val pixels = IntArray(width * height) {
            Argb.rgb((it * 53) % 256, 128, (it * 97) % 256)
        }
        assertArrayEquals(pixels, roundTrip(pixels, width, height, TiffCodec.Compression.DEFLATE).pixels)
    }

    @Test fun deflateActuallyShrinksRepeatedData() {
        val pixels = IntArray(64 * 64) { Argb.rgb(200, 100, 50) }
        val comp = ByteArrayOutputStream()
        val raw = ByteArrayOutputStream()
        TiffCodec.writePixels(64, 64, pixels, comp, TiffCodec.Compression.DEFLATE)
        TiffCodec.writePixels(64, 64, pixels, raw, TiffCodec.Compression.NONE)
        assertTrue("Deflate debía reducir el tamaño", comp.size() < raw.size())
    }

    @Test fun giantStripedImageUsesMultipleStrips() {
        // More than ROWS_PER_STRIP (256) rows -> multi-strip out-of-line offsets.
        val width = 8
        val height = 600
        val pixels = IntArray(width * height) { Argb.rgb(it % 256, (it * 3) % 256, (it * 7) % 256) }
        assertArrayEquals(pixels, roundTrip(pixels, width, height, TiffCodec.Compression.NONE).pixels)
    }

    // ------------------------------------------------------- hand-built fixtures

    private class T(val tag: Int, val type: Int, val count: Int, val value: Long)

    /** Minimal little-endian 8-bit grayscale TIFF with a single raw strip. */
    private fun buildTiff(width: Int, height: Int, photometric: Int, compression: Int, rawStrip: ByteArray): ByteArray {
        val entries = listOf(
            T(256, 4, 1, width.toLong()),
            T(257, 4, 1, height.toLong()),
            T(258, 3, 1, 8),
            T(259, 3, 1, compression.toLong()),
            T(262, 3, 1, photometric.toLong()),
            T(273, 4, 1, 0), // patched below
            T(277, 3, 1, 1),
            T(278, 4, 1, height.toLong()),
            T(279, 4, 1, 0), // patched below
        )
        val ifdStart = 8
        val ifdSize = 2 + entries.size * 12 + 4
        val stripOffset = ifdStart + ifdSize
        val buffer = ByteBuffer.allocate(stripOffset + rawStrip.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.putShort(42)
        buffer.putInt(ifdStart)
        buffer.putShort(entries.size.toShort())
        for (entry in entries) {
            buffer.putShort(entry.tag.toShort())
            buffer.putShort(entry.type.toShort())
            buffer.putInt(entry.count)
            val value = when (entry.tag) {
                273 -> stripOffset.toLong()
                279 -> rawStrip.size.toLong()
                else -> entry.value
            }
            if (entry.type == 3) {
                buffer.putShort(value.toShort())
                buffer.putShort(0)
            } else {
                buffer.putInt(value.toInt())
            }
        }
        buffer.putInt(0) // no next IFD
        buffer.put(rawStrip)
        return buffer.array()
    }

    @Test fun readPackBitsLiteralAndRepeatRuns() {
        // PackBits: [0xFF, 200] = repeat 200 twice; [0x00, 50] and [0x00, 60] = literals.
        val strip = byteArrayOf(0xFF.toByte(), 200.toByte(), 0x00, 50, 0x00, 60)
        val bytes = buildTiff(2, 2, 1, 32773, strip)
        val image = TiffCodec.decodePixels(bytes)
        val expected = intArrayOf(
            Argb.rgb(200, 200, 200), Argb.rgb(200, 200, 200),
            Argb.rgb(50, 50, 50), Argb.rgb(60, 60, 60),
        )
        assertArrayEquals(expected, image.pixels)
    }

    @Test fun readBigEndianTiff() {
        // Build a minimal big-endian uncompressed TIFF with 4 gray values.
        val strip = byteArrayOf(10, 20, 30, 40)
        val entries = listOf(
            T(256, 4, 1, 2), T(257, 4, 1, 2), T(258, 3, 1, 8),
            T(259, 3, 1, 1), T(262, 3, 1, 1), T(273, 4, 1, 0),
            T(277, 3, 1, 1), T(278, 4, 1, 2), T(279, 4, 1, 0),
        )
        val ifdStart = 8
        val ifdSize = 2 + entries.size * 12 + 4
        val stripOffset = ifdStart + ifdSize
        val buffer = ByteBuffer.allocate(stripOffset + strip.size).order(ByteOrder.BIG_ENDIAN)
        buffer.put('M'.code.toByte())
        buffer.put('M'.code.toByte())
        buffer.putShort(42)
        buffer.putInt(ifdStart)
        buffer.putShort(entries.size.toShort())
        for (entry in entries) {
            buffer.putShort(entry.tag.toShort())
            buffer.putShort(entry.type.toShort())
            buffer.putInt(entry.count)
            val value = when (entry.tag) {
                273 -> stripOffset.toLong()
                279 -> strip.size.toLong()
                else -> entry.value
            }
            if (entry.type == 3) {
                buffer.putShort(value.toShort())
                buffer.putShort(0)
            } else {
                buffer.putInt(value.toInt())
            }
        }
        buffer.putInt(0)
        buffer.put(strip)

        val image = TiffCodec.decodePixels(buffer.array())
        val expected = intArrayOf(
            Argb.rgb(10, 10, 10), Argb.rgb(20, 20, 20),
            Argb.rgb(30, 30, 30), Argb.rgb(40, 40, 40),
        )
        assertArrayEquals(expected, image.pixels)
    }

    @Test fun unsupportedCompressionIsRejected() {
        val bytes = buildTiff(1, 1, 1, 77, byteArrayOf(0x01))
        val rejected = runCatching { TiffCodec.decodePixels(bytes) }
        assertTrue(rejected.exceptionOrNull() is UnsupportedFormatException)
    }

    @Test fun tiledTiffIsRejectedWithClearMessage() {
        // Adding tags 322/324 (tile width / tiled strip offsets) must be rejected.
        val entries = listOf(
            T(256, 4, 1, 1), T(257, 4, 1, 1), T(258, 3, 1, 8),
            T(259, 3, 1, 1), T(262, 3, 1, 1), T(273, 4, 1, 0),
            T(277, 3, 1, 1), T(278, 4, 1, 1), T(279, 4, 1, 0),
            T(322, 4, 1, 16), T(323, 4, 1, 16),
        )
        val ifdStart = 8
        val ifdSize = 2 + entries.size * 12 + 4
        val stripOffset = ifdStart + ifdSize
        val buffer = ByteBuffer.allocate(stripOffset + 1).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.putShort(42)
        buffer.putInt(ifdStart)
        buffer.putShort(entries.size.toShort())
        for (entry in entries) {
            buffer.putShort(entry.tag.toShort())
            buffer.putShort(entry.type.toShort())
            buffer.putInt(entry.count)
            val value = when (entry.tag) {
                273 -> stripOffset.toLong()
                279 -> 1L
                else -> entry.value
            }
            if (entry.type == 3) {
                buffer.putShort(value.toShort())
                buffer.putShort(0)
            } else {
                buffer.putInt(value.toInt())
            }
        }
        buffer.putInt(0)
        buffer.put(0x01.toByte())

        val rejected = runCatching { TiffCodec.decodePixels(buffer.array()) }
        assertTrue(rejected.exceptionOrNull() is UnsupportedFormatException)
        assertTrue("El mensaje debía hablar de mosaicos: ${rejected.exceptionOrNull()?.message}", rejected.exceptionOrNull()!!.message!!.contains("mosaicos"))
    }

    @Test fun garbageThrowsUnsupportedFormat() {
        val rejected = runCatching { TiffCodec.decodePixels("basura".toByteArray()) }
        assertTrue(rejected.exceptionOrNull() is UnsupportedFormatException)
    }
}