package com.pardocs.app.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.pardocs.app.core.Formats
import com.pardocs.app.core.Storage
import com.pardocs.app.core.WorkFile
import com.pardocs.app.engine.codec.BmpCodec
import com.pardocs.app.engine.codec.TiffCodec
import java.io.File
import java.io.FileOutputStream

/**
 * Image decoding / encoding. JPG, PNG, WEBP, BMP, TIFF, HEIC, HEIF, GIF, AVIF
 * decode on-device; JPG, PNG, WEBP, BMP and TIFF encode on-device. Formats that
 * cannot be encoded are reported truthfully (see [com.pardocs.app.core.Formats]).
 */
object ImageEngine {

    data class ConvertOptions(
        val format: String,
        val quality: Int = 90,
        val maxDimension: Int? = null,
    )

    fun decode(file: File, maxDimension: Int = 8192): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        if (options.outWidth > 0 && options.outHeight > 0) {
            val decodeOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                var sample = 1
                val longest = maxOf(options.outWidth, options.outHeight)
                while (longest / (sample * 2) >= maxDimension && sample <= 64) sample *= 2
                inSampleSize = sample
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            if (decoded != null) return applyOrientation(file, decoded)
        }

        // Android's BitmapFactory cannot decode TIFF (and some BMP variants).
        return when (file.extension.lowercase()) {
            "tif", "tiff" -> TiffCodec.read(file)
            "bmp", "dib" -> BmpCodec.read(file)
            else -> throw UnsupportedFormatException(
                "No se pudo decodificar «${file.name}». Si es un formato de imagen no soportado por Android, prueba a convertirlo en otro dispositivo o con otra herramienta.",
            )
        }
    }

    private fun applyOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return bitmap
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Encodes [bitmap] into [outFile] in [format]. Throws
     * [UnsupportedFormatException] for formats Android cannot write.
     */
    fun encode(bitmap: Bitmap, outFile: File, format: String, quality: Int) {
        val extension = format.lowercase()
        when (extension) {
            "jpg", "jpeg" -> {
                val opaque = flattenOnWhite(bitmap)
                FileOutputStream(outFile).use {
                    opaque.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), it)
                }
                if (opaque !== bitmap) opaque.recycle()
            }
            "png" -> FileOutputStream(outFile).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            "webp" -> {
                val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                FileOutputStream(outFile).use {
                    bitmap.compress(compressFormat, quality.coerceIn(1, 100), it)
                }
            }
            "bmp", "dib" -> FileOutputStream(outFile).use { BmpCodec.write(bitmap, it) }
            "tif", "tiff" -> FileOutputStream(outFile).use {
                TiffCodec.write(bitmap, it, TiffCodec.Compression.DEFLATE)
            }
            else -> throw UnsupportedFormatException(
                Formats.describeUnsupportedImageTarget(extension)
                    ?: "El formato de salida «$extension» no está soportado."
            )
        }
    }

    /** Mass format conversion. */
    suspend fun convert(
        files: List<WorkFile>,
        outDir: File,
        options: ConvertOptions,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        val outputs = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        val unsupportedTarget = Formats.describeUnsupportedImageTarget(options.format.lowercase())
        if (unsupportedTarget != null) throw UnsupportedFormatException(unsupportedTarget)

        files.forEachIndexed { index, workFile ->
            checkCancelled()
            onProgress(index.toFloat() / files.size, "Convirtiendo ${workFile.displayName}")
            runCatching {
                val bitmap = decode(workFile.file, maxDimension = options.maxDimension ?: 8192)
                try {
                    val extension = if (options.format == "jpg") "jpg" else options.format.lowercase()
                    val target = Storage.uniqueFile(
                        outDir,
                        workFile.displayName.substringBeforeLast('.'),
                        extension,
                    )
                    encode(bitmap, target, extension, options.quality)
                    outputs += target
                } finally {
                    bitmap.recycle()
                }
            }.onFailure { error ->
                warnings += "«${workFile.displayName}»: ${error.message}"
            }
        }
        if (outputs.isEmpty()) {
            throw EngineException("Ninguna imagen pudo convertirse.")
        }
        return EngineOutcome(outputs, warnings)
    }

    /** True recompression: downscales to [maxDimension] and re-encodes. */
    suspend fun optimize(
        files: List<WorkFile>,
        outDir: File,
        options: ConvertOptions,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        val outputs = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        files.forEachIndexed { index, workFile ->
            checkCancelled()
            onProgress(index.toFloat() / files.size, "Optimizando ${workFile.displayName}")
            val before = workFile.file.length()
            runCatching {
                val bitmap = decode(workFile.file, maxDimension = options.maxDimension ?: 1920)
                try {
                    val extension = if (options.format.lowercase() == "jpg") "jpg" else options.format.lowercase()
                    val target = Storage.uniqueFile(
                        outDir,
                        workFile.displayName.substringBeforeLast('.'),
                        extension,
                    )
                    encode(bitmap, target, extension, options.quality)
                    val after = target.length()
                    if (after >= before) {
                        warnings += "«${workFile.displayName}» no se redujo (${Storage.formatSize(before)} → ${Storage.formatSize(after)})."
                    }
                    outputs += target
                } finally {
                    bitmap.recycle()
                }
            }.onFailure { error ->
                warnings += "«${workFile.displayName}»: ${error.message}"
            }
        }
        if (outputs.isEmpty()) throw EngineException("Ninguna imagen pudo optimizarse.")
        return EngineOutcome(outputs, warnings)
    }

    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return flattened
    }
}