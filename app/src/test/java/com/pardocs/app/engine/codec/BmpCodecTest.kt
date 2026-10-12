package com.pardocs.app.engine.codec

import com.pardocs.app.engine.UnsupportedFormatException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BmpCodecTest {

    private fun roundTrip(pixels: IntArray, width: Int, height: Int): PixelImage {
        val out = ByteArrayOutputStream()
        BmpCodec.writePixels(width, height, pixels, out)
        val image = BmpCodec.decodePixels(out.toByteArray())
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        return image
    }

    @Test fun roundTrip24BitPreservesPixels() {
        val width = 3
        val height = 2
        val pixels = intArrayOf(
            Argb.rgb(255, 0, 0), Argb.rgb(0, 255, 0), Argb.rgb(0, 0, 255),
            Argb.rgb(10, 20, 30), Argb.rgb(200, 150, 100), Argb.rgb(255, 255, 255),
        )
        assertArrayEquals(pixels, roundTrip(pixels, width, height).pixels)
    }

    @Test fun roundTripMultipleRowsAndPadding() {
        // 5 x 3 exercises the 4-byte row-alignment padding (5 * 3 = 15, padded to 16).
        val width = 5
        val height = 3
        val pixels = IntArray(width * height) { Argb.rgb((it * 17) % 256, (it * 31) % 256, (it * 47) % 256) }
        assertArrayEquals(pixels, roundTrip(pixels, width, height).pixels)
    }

    @Test fun indexed8BitBmpUsesPalette() {
        val width = 2
        val height = 2
        val dataOffset = 54 + 256 * 4
        val rowStride = 4
        val fileSize = dataOffset + rowStride * height
        val buffer = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('B'.code.toByte())
        buffer.put('M'.code.toByte())
        buffer.putInt(fileSize)
        buffer.putInt(0)
        buffer.putInt(dataOffset)
        buffer.putInt(40) // BITMAPINFOHEADER
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putShort(1)
        buffer.putShort(8) // 8 bpp
        buffer.putInt(0) // BI_RGB
        buffer.putInt(rowStride * height)
        buffer.putInt(2835)
        buffer.putInt(2835)
        buffer.putInt(2) // colours used
        buffer.putInt(0)
        repeat(256) { index ->
            when (index) {
                0 -> buffer.put(byteArrayOf(0, 0, 0, 0))     // black (BGR)
                1 -> buffer.put(byteArrayOf(0, 0, 255.toByte(), 0)) // red (BGR)
                else -> buffer.put(byteArrayOf(0, 0, 0, 0))
            }
        }
        // Bottom-up rows. File row 0 = image row 1, file row 1 = image row 0.
        buffer.put(byteArrayOf(0x00, 0x01, 0x00, 0x00)) // image row 1: black, red
        buffer.put(byteArrayOf(0x01, 0x00, 0x00, 0x00)) // image row 0: red, black

        val image = BmpCodec.decodePixels(buffer.array())
        val expected = intArrayOf(
            Argb.rgb(255, 0, 0), Argb.rgb(0, 0, 0),
            Argb.rgb(0, 0, 0), Argb.rgb(255, 0, 0),
        )
        assertArrayEquals(expected, image.pixels)
    }

    @Test fun thirtyTwoBitBmpKeepsAlphaWhenPresent() {
        val dataOffset = 54
        val buffer = ByteBuffer.allocate(dataOffset + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('B'.code.toByte())
        buffer.put('M'.code.toByte())
        buffer.putInt(dataOffset + 4)
        buffer.putInt(0)
        buffer.putInt(dataOffset)
        buffer.putInt(40)
        buffer.putInt(1)
        buffer.putInt(1)
        buffer.putShort(1)
        buffer.putShort(32)
        buffer.putInt(0)
        buffer.putInt(4)
        buffer.putInt(2835)
        buffer.putInt(2835)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.put(byteArrayOf(30, 20, 10, 128.toByte())) // B,G,R,A
        val image = BmpCodec.decodePixels(buffer.array())
        assertEquals(Argb.argb(128, 10, 20, 30), image.pixels[0])
    }

    @Test fun sixteenBit565ExpandsTo24Bit() {
        val dataOffset = 54
        val buffer = ByteBuffer.allocate(dataOffset + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('B'.code.toByte())
        buffer.put('M'.code.toByte())
        buffer.putInt(dataOffset + 4)
        buffer.putInt(0)
        buffer.putInt(dataOffset)
        buffer.putInt(40)
        buffer.putInt(2)
        buffer.putInt(1)
        buffer.putShort(1)
        buffer.putShort(16)
        buffer.putInt(0)
        buffer.putInt(4)
        buffer.putInt(2835)
        buffer.putInt(2835)
        buffer.putInt(0)
        buffer.putInt(0)
        // 0xFFFF -> white, 0x0000 -> black (5-5-5 with expansion).
        buffer.putShort(0xFFFF.toShort())
        buffer.putShort(0x0000)
        val image = BmpCodec.decodePixels(buffer.array())
        assertEquals(Argb.rgb(255, 255, 255), image.pixels[0])
        assertEquals(Argb.rgb(0, 0, 0), image.pixels[1])
    }

    @Test fun invalidFileThrowsUnsupportedFormat() {
        val rejected = runCatching { BmpCodec.decodePixels("no es un bmp".toByteArray()) }
        assertTrue(rejected.exceptionOrNull() is UnsupportedFormatException)
    }
}