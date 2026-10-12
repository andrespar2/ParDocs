package com.pardocs.app.engine.codec

import android.graphics.Bitmap
import com.pardocs.app.engine.UnsupportedFormatException
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal but complete Windows Bitmap (BMP) reader/writer.
 *
 * Write: 24-bit BI_RGB, bottom-up, 4-byte aligned rows.
 * Read: BI_RGB at 8/24/32 bpp (palette supported), plus 16-bit 5-5-5.
 */
object BmpCodec {

    fun write(bitmap: Bitmap, out: OutputStream) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        writePixels(bitmap.width, bitmap.height, pixels, out)
    }

    fun writePixels(width: Int, height: Int, pixels: IntArray, out: OutputStream) {
        val rowStride = ((24 * width + 31) / 32) * 4
        val pixelBytes = rowStride * height
        val dataOffset = 14 + 40
        val fileSize = dataOffset + pixelBytes

        val header = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        header.put('B'.code.toByte())
        header.put('M'.code.toByte())
        header.putInt(fileSize)
        header.putInt(0)
        header.putInt(dataOffset)
        // BITMAPINFOHEADER
        header.putInt(40)
        header.putInt(width)
        header.putInt(height)
        header.putShort(1)
        header.putShort(24)
        header.putInt(0)
        header.putInt(pixelBytes)
        header.putInt(2835)
        header.putInt(2835)
        header.putInt(0)
        header.putInt(0)
        out.write(header.array())

        val row = ByteArray(rowStride)
        for (y in height - 1 downTo 0) {
            var cursor = 0
            for (x in 0 until width) {
                val color = pixels[y * width + x]
                row[cursor++] = (color and 0xFF).toByte()
                row[cursor++] = ((color shr 8) and 0xFF).toByte()
                row[cursor++] = ((color shr 16) and 0xFF).toByte()
            }
            while (cursor < rowStride) row[cursor++] = 0
            out.write(row)
        }
    }

    fun read(file: File): Bitmap {
        val bytes = file.readBytes()
        if (bytes.size < 54 || bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) {
            throw UnsupportedFormatException("«${file.name}» no es un BMP válido.")
        }
        try {
            return decodePixels(bytes).toBitmap()
        } catch (error: UnsupportedFormatException) {
            throw error
        } catch (error: Exception) {
            throw UnsupportedFormatException("No se pudo decodificar «${file.name}» como BMP: ${error.message}")
        }
    }

    /** Pixel-level decoder (no Android dependencies) so it can be unit tested on the JVM. */
    fun decodePixels(bytes: ByteArray): PixelImage {
        // Validate the signature/size here too: this entry point has no Android
        // Bitmap fallback, so a bad input must fail with a presentable error.
        if (bytes.size < 54 || bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) {
            throw UnsupportedFormatException("El archivo no es un BMP válido (cabecera «BM» ausente).")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(10)
        val dataOffset = buffer.int
        buffer.position(14)
        val headerSize = buffer.int
        val width = buffer.int
        val rawHeight = buffer.int
        buffer.short // planes
        val bitsPerPixel = buffer.short.toInt() and 0xFFFF
        val compression = buffer.int

        val height = if (rawHeight < 0) -rawHeight else rawHeight
        val topDown = rawHeight < 0
        if (width <= 0 || height <= 0) throw UnsupportedFormatException("BMP con dimensiones no válidas.")
        if (compression != 0 && compression != 3) {
            throw UnsupportedFormatException("BMP con compresión $compression no soportada (usa un BMP sin comprimir).")
        }

        var palette: IntArray? = null
        var paletteStart = 14 + headerSize
        val paletteEntries = when (bitsPerPixel) {
            1 -> 2
            4 -> 16
            8 -> 256
            else -> 0
        }
        if (paletteEntries > 0) {
            if (headerSize >= 40 && headerSize != 40) {
                // BITMAPV4/V5 headers carry explicit masks before the palette.
                paletteStart += (headerSize - 40)
            }
            val colors = IntArray(paletteEntries)
            var cursor = paletteStart
            for (index in 0 until paletteEntries) {
                val blue = bytes[cursor].toInt() and 0xFF
                val green = bytes[cursor + 1].toInt() and 0xFF
                val red = bytes[cursor + 2].toInt() and 0xFF
                colors[index] = Argb.rgb(red, green, blue)
                cursor += 4
            }
            palette = colors
        }

        val pixels = IntArray(width * height)
        val rowStride = ((bitsPerPixel * width + 31) / 32) * 4

        for (row in 0 until height) {
            val sourceRow = if (topDown) row else height - 1 - row
            val rowStart = dataOffset + sourceRow * rowStride
            when (bitsPerPixel) {
                24 -> {
                    var cursor = rowStart
                    for (x in 0 until width) {
                        val blue = bytes[cursor].toInt() and 0xFF
                        val green = bytes[cursor + 1].toInt() and 0xFF
                        val red = bytes[cursor + 2].toInt() and 0xFF
                        pixels[row * width + x] = Argb.rgb(red, green, blue)
                        cursor += 3
                    }
                }
                32 -> {
                    var cursor = rowStart
                    for (x in 0 until width) {
                        val blue = bytes[cursor].toInt() and 0xFF
                        val green = bytes[cursor + 1].toInt() and 0xFF
                        val red = bytes[cursor + 2].toInt() and 0xFF
                        val alpha = bytes[cursor + 3].toInt() and 0xFF
                        pixels[row * width + x] =
                            if (alpha == 0 && compression == 0) Argb.rgb(red, green, blue)
                            else Argb.argb(alpha, red, green, blue)
                        cursor += 4
                    }
                }
                16 -> {
                    var cursor = rowStart
                    for (x in 0 until width) {
                        val value = (bytes[cursor].toInt() and 0xFF) or ((bytes[cursor + 1].toInt() and 0xFF) shl 8)
                        val red = ((value shr 10) and 0x1F) shl 3
                        val green = ((value shr 5) and 0x1F) shl 3
                        val blue = (value and 0x1F) shl 3
                        pixels[row * width + x] = Argb.rgb(red or (red shr 5), green or (green shr 5), blue or (blue shr 5))
                        cursor += 2
                    }
                }
                8 -> {
                    val colors = palette ?: throw UnsupportedFormatException("BMP indexado sin paleta.")
                    var cursor = rowStart
                    for (x in 0 until width) {
                        pixels[row * width + x] = colors[bytes[cursor].toInt() and 0xFF]
                        cursor += 1
                    }
                }
                4 -> {
                    val colors = palette ?: throw UnsupportedFormatException("BMP indexado sin paleta.")
                    var cursor = rowStart
                    var x = 0
                    while (x < width) {
                        val value = bytes[cursor++].toInt() and 0xFF
                        pixels[row * width + x] = colors[(value shr 4) and 0x0F]
                        if (x + 1 < width) pixels[row * width + x + 1] = colors[value and 0x0F]
                        x += 2
                    }
                }
                1 -> {
                    val colors = palette ?: throw UnsupportedFormatException("BMP indexado sin paleta.")
                    var cursor = rowStart
                    var x = 0
                    while (x < width) {
                        val value = bytes[cursor++].toInt() and 0xFF
                        for (bit in 0 until 8) {
                            if (x >= width) break
                            val set = (value shr (7 - bit)) and 1
                            pixels[row * width + x] = colors[set]
                            x++
                        }
                    }
                }
                else -> throw UnsupportedFormatException("BMP de $bitsPerPixel bits por píxel no soportado.")
            }
        }
        return PixelImage(width, height, pixels)
    }
}
