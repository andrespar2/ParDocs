package com.pardocs.app.engine.codec

import android.graphics.Bitmap
import com.pardocs.app.engine.UnsupportedFormatException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Baseline TIFF codec (pure Kotlin).
 *
 * Write: RGB 8-bit, strip organised, uncompressed or zlib Deflate.
 * Read: 8/16-bit grayscale, palette and RGB, planar configuration 1,
 *       compression none / Deflate / PackBits / LZW, horizontal predictor.
 *
 * Tiled TIFFs and CMYK are intentionally reported as unsupported rather than
 * being guessed at.
 */
object TiffCodec {

    enum class Compression(val code: Int, val label: String) {
        NONE(1, "Sin compresión"),
        DEFLATE(8, "Deflate (zlib)"),
    }

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5

    private const val ROWS_PER_STRIP = 256

    // ------------------------------------------------------------------- write

    fun write(bitmap: Bitmap, out: OutputStream, compression: Compression = Compression.DEFLATE) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        writePixels(bitmap.width, bitmap.height, pixels, out, compression)
    }

    /** Pixel-level writer (no Android dependencies) so it can be unit tested on the JVM. */
    fun writePixels(
        width: Int,
        height: Int,
        pixels: IntArray,
        out: OutputStream,
        compression: Compression = Compression.DEFLATE,
    ) {
        val rgb = rgbPixels(width, height, pixels)

        val strips = ArrayList<ByteArray>()
        var row = 0
        while (row < height) {
            val rows = minOf(ROWS_PER_STRIP, height - row)
            val chunk = rgb.copyOfRange(row * width * 3, (row + rows) * width * 3)
            strips += when (compression) {
                Compression.NONE -> chunk
                Compression.DEFLATE -> deflate(chunk)
            }
            row += rows
        }

        val entries = ArrayList<OutEntry>()
        entries += OutEntry(256, TYPE_LONG, 1, inline = width.toLong())
        entries += OutEntry(257, TYPE_LONG, 1, inline = height.toLong())
        entries += OutEntry(258, TYPE_SHORT, 3, payload = shorts(8, 8, 8))
        entries += OutEntry(259, TYPE_SHORT, 1, inline = compression.code.toLong())
        entries += OutEntry(262, TYPE_SHORT, 1, inline = 2)
        entries += OutEntry(273, TYPE_LONG, strips.size, payload = if (strips.size == 1) null else longs(*LongArray(strips.size) { 0 }))
        entries += OutEntry(277, TYPE_SHORT, 1, inline = 3)
        entries += OutEntry(278, TYPE_LONG, 1, inline = ROWS_PER_STRIP.toLong())
        entries += OutEntry(279, TYPE_LONG, strips.size, payload = if (strips.size == 1) null else longs(*strips.map { it.size.toLong() }.toLongArray()))
        entries += OutEntry(282, TYPE_RATIONAL, 1, payload = longs(72, 1))
        entries += OutEntry(283, TYPE_RATIONAL, 1, payload = longs(72, 1))
        entries += OutEntry(284, TYPE_SHORT, 1, inline = 1)
        entries += OutEntry(296, TYPE_SHORT, 1, inline = 2)
        entries += OutEntry(305, TYPE_ASCII, "ParDocs".length + 1, payload = "ParDocs".toByteArray(Charsets.US_ASCII) + 0)
        entries += OutEntry(339, TYPE_SHORT, 3, payload = shorts(1, 1, 1))

        val ifdStart = 8
        val ifdSize = 2 + entries.size * 12 + 4

        var payloadCursor = ifdStart + ifdSize
        val payloadOffsets = HashMap<OutEntry, Int>()
        for (entry in entries) {
            val payload = entry.payload
            if (payload != null) {
                if (payloadCursor % 2 != 0) payloadCursor++
                payloadOffsets[entry] = payloadCursor
                payloadCursor += payload.size
            }
        }
        if (payloadCursor % 2 != 0) payloadCursor++
        var stripCursor = payloadCursor

        val stripOffsets = LongArray(strips.size)
        strips.forEachIndexed { index, bytes ->
            stripOffsets[index] = stripCursor.toLong()
            stripCursor += bytes.size
            if (stripCursor % 2 != 0) stripCursor++
        }
        // Patch strip offsets / byte counts now that real values are known.
        // TIFF stores <= 4-byte values inline in the entry field, so single-strip
        // images keep 273/279 inline (our reader relies on that convention).
        for (entry in entries) {
            if (entry.tag == 273) {
                if (strips.size == 1) entry.inline = stripOffsets[0] else entry.payload = longs(*stripOffsets)
            } else if (entry.tag == 279 && strips.size == 1) {
                entry.inline = strips[0].size.toLong()
            }
        }

        val totalSize = stripCursor
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.putShort(42)
        buffer.putInt(ifdStart)
        buffer.putShort(entries.size.toShort())
        for (entry in entries) {
            buffer.putShort(entry.tag.toShort())
            buffer.putShort(entry.type.toShort())
            buffer.putInt(entry.count)
            val payload = entry.payload
            if (payload != null) {
                buffer.putInt(payloadOffsets.getValue(entry))
            } else {
                if (entry.type == TYPE_SHORT) {
                    buffer.putShort(entry.inline.toShort())
                    buffer.putShort(0)
                } else {
                    buffer.putInt(entry.inline.toInt())
                }
            }
        }
        buffer.putInt(0) // no further IFD

        for (entry in entries) {
            val payload = entry.payload ?: continue
            buffer.position(payloadOffsets.getValue(entry))
            buffer.put(payload)
        }
        strips.forEachIndexed { index, bytes ->
            buffer.position(stripOffsets[index].toInt())
            buffer.put(bytes)
        }

        out.write(buffer.array())
    }

    private fun rgbPixels(width: Int, height: Int, pixels: IntArray): ByteArray {
        val result = ByteArray(width * height * 3)
        var cursor = 0
        for (i in pixels.indices) {
            val color = pixels[i]
            result[cursor++] = ((color shr 16) and 0xFF).toByte()
            result[cursor++] = ((color shr 8) and 0xFF).toByte()
            result[cursor++] = (color and 0xFF).toByte()
        }
        return result
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val output = ByteArrayOutputStream(data.size / 2 + 64)
            val chunk = ByteArray(16 * 1024)
            while (!deflater.finished()) {
                val written = deflater.deflate(chunk)
                output.write(chunk, 0, written)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    // -------------------------------------------------------------------- read

    fun read(file: File): Bitmap {
        val bytes = file.readBytes()
        return try {
            decodePixels(bytes).toBitmap()
        } catch (error: UnsupportedFormatException) {
            throw error
        } catch (error: Exception) {
            throw UnsupportedFormatException("No se pudo decodificar «${file.name}» como TIFF: ${error.message}")
        }
    }

    private class Entry(var type: Int, var count: Int, var valuePosition: Int, var valueOrOffset: Int)

    /** Pixel-level decoder (no Android dependencies) so it can be unit tested on the JVM. */
    fun decodePixels(bytes: ByteArray): PixelImage {
        if (bytes.size < 8) throw UnsupportedFormatException("Archivo TIFF truncado.")
        val order = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw UnsupportedFormatException("Cabecera TIFF no reconocida.")
        }
        val buffer = ByteBuffer.wrap(bytes).order(order)
        buffer.position(2)
        if ((buffer.short.toInt() and 0xFFFF) != 42) throw UnsupportedFormatException("TIFF no válido (falta la firma 42).")
        val ifdOffset = buffer.int

        buffer.position(ifdOffset)
        val entryCount = buffer.short.toInt() and 0xFFFF
        if (entryCount <= 0 || entryCount > 1024) throw UnsupportedFormatException("Directorio TIFF no válido.")
        val entries = HashMap<Int, Entry>(entryCount * 2)
        for (index in 0 until entryCount) {
            val tag = buffer.short.toInt() and 0xFFFF
            val type = buffer.short.toInt() and 0xFFFF
            val count = buffer.int
            val valuePosition = buffer.position()
            val valueOrOffset = buffer.int
            entries[tag] = Entry(type, count, valuePosition, valueOrOffset)
        }

        fun values(tag: Int): LongArray? {
            val entry = entries[tag] ?: return null
            val unit = typeSize(entry.type)
            val total = unit * entry.count
            val start = if (total <= 4) entry.valuePosition else entry.valueOrOffset
            val result = LongArray(entry.count)
            for (index in 0 until entry.count) {
                val position = start + index * unit
                result[index] = when (entry.type) {
                    TYPE_SHORT -> (buffer.getShort(position).toInt() and 0xFFFF).toLong()
                    TYPE_LONG -> (buffer.getInt(position).toLong() and 0xFFFFFFFFL)
                    TYPE_RATIONAL -> (buffer.getInt(position).toLong() and 0xFFFFFFFFL)
                    else -> (buffer.get(position).toLong() and 0xFF)
                }
            }
            return result
        }

        fun single(tag: Int): Long? = values(tag)?.firstOrNull()

        if (entries.containsKey(324) || entries.containsKey(322)) {
            throw UnsupportedFormatException("Los TIFF con mosaicos (tiled) no están soportados. Vuelve a guardarlo como TIFF por franjas o conviértelo a PNG.")
        }

        val width = single(256)?.toInt() ?: throw UnsupportedFormatException("TIFF sin anchura declarada.")
        val height = single(257)?.toInt() ?: throw UnsupportedFormatException("TIFF sin altura declarada.")
        if (width <= 0 || height <= 0) throw UnsupportedFormatException("Dimensiones TIFF no válidas.")
        if (width.toLong() * height > 100_000_000L) throw UnsupportedFormatException("Imagen TIFF demasiado grande para procesarla en memoria.")

        val compression = (single(259) ?: 1L).toInt()
        val photometric = (single(262) ?: 2L).toInt()
        val samplesPerPixel = (single(277) ?: 3L).toInt()
        val planar = (single(284) ?: 1L).toInt()
        val predictor = (single(317) ?: 1L).toInt()
        val bits = values(258)?.map { it.toInt() } ?: listOf(8)
        val stripOffsets = values(273) ?: throw UnsupportedFormatException("TIFF sin datos de imagen.")
        val stripCounts = values(279)
        val colorMap = values(320)

        if (planar != 1) throw UnsupportedFormatException("Los TIFF con planos separados (PlanarConfiguration=2) no están soportados.")
        if (samplesPerPixel > 1 && (photometric != 2 && photometric != 6)) {
            throw UnsupportedFormatException("TIFF con interpretación fotométrica $photometric no soportada (CMYK no es compatible).")
        }
        if (bits.any { it != 8 && it != 16 }) {
            throw UnsupportedFormatException("TIFF de ${bits.firstOrNull()} bits por muestra no soportado (solo 8 y 16).")
        }

        val bytesPerSample = if (bits.first() == 16) 2 else 1
        val bytesPerPixel = bytesPerSample * (if (photometric == 2 || photometric == 6) samplesPerPixel else 1)

        val rawOutput = ByteArrayOutputStream(width * height * bytesPerPixel)
        stripOffsets.forEachIndexed { index, offset ->
            val length = stripCounts?.getOrNull(index)?.toInt()
                ?: (stripOffsets.getOrNull(index + 1)?.toInt() ?: bytes.size) - offset.toInt()
            if (offset < 0 || length <= 0 || offset + length > bytes.size) return@forEachIndexed
            val slice = bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
            val decoded = decompress(slice, compression)
            rawOutput.write(decoded)
        }
        var samples = rawOutput.toByteArray()
        if (predictor == 2) samples = undoHorizontalPredictor(samples, width, bytesPerPixel)

        val expectedSamples = width * height * bytesPerPixel
        if (samples.size < expectedSamples) {
            throw UnsupportedFormatException("Los datos TIFF están incompletos o usan una disposición no soportada.")
        }

        val pixels = IntArray(width * height)
        val sampleBuffer = ByteBuffer.wrap(samples).order(order)
        for (pixel in 0 until width * height) {
            val base = pixel * bytesPerPixel
            val color = when {
                photometric == 2 || photometric == 6 -> {
                    val red = sampleAt(sampleBuffer, base, bytesPerSample)
                    val green = sampleAt(sampleBuffer, base + bytesPerSample, bytesPerSample)
                    val blue = sampleAt(sampleBuffer, base + 2 * bytesPerSample, bytesPerSample)
                    val alpha = if (samplesPerPixel >= 4) sampleAt(sampleBuffer, base + 3 * bytesPerSample, bytesPerSample) else 255
                    Argb.argb(alpha, red, green, blue)
                }
                photometric == 1 -> {
                    val gray = sampleAt(sampleBuffer, base, bytesPerSample)
                    Argb.rgb(gray, gray, gray)
                }
                photometric == 0 -> {
                    val gray = 255 - sampleAt(sampleBuffer, base, bytesPerSample)
                    Argb.rgb(gray, gray, gray)
                }
                photometric == 3 -> {
                    val tableSize = 1 shl bits.first()
                    val map = colorMap ?: throw UnsupportedFormatException("TIFF indexado sin paleta.")
                    val index = sampleAt(sampleBuffer, base, bytesPerSample).coerceIn(0, tableSize - 1)
                    val red = (map.getOrNull(index) ?: 0L).toInt() shr 8
                    val green = (map.getOrNull(tableSize + index) ?: 0L).toInt() shr 8
                    val blue = (map.getOrNull(2 * tableSize + index) ?: 0L).toInt() shr 8
                    Argb.rgb(red and 0xFF, green and 0xFF, blue and 0xFF)
                }
                else -> throw UnsupportedFormatException("TIFF con interpretación fotométrica $photometric no soportada.")
            }
            pixels[pixel] = color
        }
        return PixelImage(width, height, pixels)
    }

    private fun sampleAt(buffer: ByteBuffer, position: Int, bytesPerSample: Int): Int {
        val raw = when (bytesPerSample) {
            2 -> buffer.getShort(position).toInt() and 0xFFFF
            else -> buffer.get(position).toInt() and 0xFF
        }
        return if (bytesPerSample == 2) raw shr 8 else raw
    }

    private fun undoHorizontalPredictor(data: ByteArray, width: Int, bytesPerPixel: Int): ByteArray {
        val result = data.copyOf()
        val rowLength = width * bytesPerPixel
        var rowStart = 0
        while (rowStart + rowLength <= result.size) {
            for (index in bytesPerPixel until rowLength) {
                val position = rowStart + index
                result[position] = (result[position] + result[position - bytesPerPixel]).toByte()
            }
            rowStart += rowLength
        }
        return result
    }

    private fun decompress(data: ByteArray, compression: Int): ByteArray = when (compression) {
        1 -> data
        8, 32946 -> {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val output = ByteArrayOutputStream(data.size * 4 + 64)
                val chunk = ByteArray(32 * 1024)
                while (!inflater.finished()) {
                    val written = inflater.inflate(chunk)
                    if (written == 0 && inflater.needsInput()) break
                    output.write(chunk, 0, written)
                }
                output.toByteArray()
            } finally {
                inflater.end()
            }
        }
        32773 -> unpackBits(data)
        5 -> lzwDecode(data)
        else -> throw UnsupportedFormatException("Compresión TIFF $compression no soportada (usa sin compresión, Deflate, PackBits o LZW).")
    }

    private fun unpackBits(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size * 4)
        var index = 0
        while (index < data.size) {
            val control = data[index++].toInt()
            when {
                control in 0..127 -> {
                    val count = control + 1
                    repeat(count) { if (index < data.size) output.write(data[index++].toInt()) }
                }
                control != -128 -> {
                    val count = 1 - control
                    if (index >= data.size) break
                    val value = data[index++].toInt()
                    repeat(count) { output.write(value) }
                }
            }
        }
        return output.toByteArray()
    }

    /** TIFF flavour of LZW: MSB-first codes, early change enabled. */
    private fun lzwDecode(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size * 4 + 64)
        var position = 0
        var bitBuffer = 0
        var bitCount = 0

        fun readBits(count: Int): Int {
            while (bitCount < count) {
                val byte = if (position < data.size) data[position++].toInt() and 0xFF else 0
                bitBuffer = (bitBuffer shl 8) or byte
                bitCount += 8
            }
            val value = (bitBuffer shr (bitCount - count)) and ((1 shl count) - 1)
            bitCount -= count
            bitBuffer = bitBuffer and ((1 shl bitCount) - 1)
            return value
        }

        var dictionary = ArrayList<ByteArray>(4096)
        fun resetDictionary() {
            dictionary = ArrayList(4096)
            for (value in 0..255) dictionary.add(byteArrayOf(value.toByte()))
            dictionary.add(ByteArray(0))
            dictionary.add(ByteArray(0))
        }
        resetDictionary()
        var codeSize = 9
        var previous: ByteArray? = null

        while (position < data.size || bitCount > 0) {
            val code = readBits(codeSize)
            if (code == 256) {
                resetDictionary()
                codeSize = 9
                previous = null
                continue
            }
            if (code == 257) break
            val entry: ByteArray = when {
                code < dictionary.size -> dictionary[code]
                code == dictionary.size && previous != null -> previous + previous[0]
                else -> throw UnsupportedFormatException("Flujo LZW corrupto en el TIFF.")
            }
            output.write(entry, 0, entry.size)
            if (previous != null && dictionary.size < 4096) {
                dictionary.add(previous + entry[0])
                if (dictionary.size - 1 == (1 shl codeSize) - 1 && codeSize < 12) codeSize++
            }
            previous = entry
            if (bitCount == 0 && position >= data.size) break
        }
        return output.toByteArray()
    }

    // ------------------------------------------------------------------ helpers

    private fun typeSize(type: Int): Int = when (type) {
        TYPE_BYTE, TYPE_ASCII -> 1
        TYPE_SHORT -> 2
        TYPE_LONG, 11 -> 4
        TYPE_RATIONAL, 12 -> 8
        else -> 1
    }

    private fun shorts(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    private fun longs(vararg values: Long): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putInt(it.toInt()) }
        return buffer.array()
    }

    private class OutEntry(
        val tag: Int,
        val type: Int,
        val count: Int,
        var inline: Long = 0L,
        var payload: ByteArray? = null,
    )
}
