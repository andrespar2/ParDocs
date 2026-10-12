package com.pardocs.app.engine

import androidx.exifinterface.media.ExifInterface
import com.pardocs.app.core.Formats
import com.pardocs.app.core.Storage
import com.pardocs.app.core.WorkFile
import java.io.File

/**
 * Reads and wipes EXIF / metadata embedded in images. Removal is lossless for
 * the pixel data (only the metadata segments are rewritten), which is exactly
 * what "borrar metadatos sin alterar la calidad" means.
 */
object ExifEngine {

    data class TagInfo(val key: String, val label: String, val category: String, val value: String)

    data class StripOptions(
        val removeAll: Boolean = false,
        val removeGps: Boolean = true,
        val removeCamera: Boolean = true,
        val removeDate: Boolean = true,
        val removeAuthor: Boolean = true,
    )

    val WRITABLE_EXTENSIONS = setOf("jpg", "jpeg", "webp", "png")

    fun canStrip(file: File): Boolean = Formats.extensionOf(file.name) in WRITABLE_EXTENSIONS

    // --------------------------------------------------------------- reading

    fun read(file: File): List<TagInfo> = runCatching {
        val exif = ExifInterface(file.absolutePath)
        TagsWithCategories.mapNotNull { (key, label, category) ->
            val value = try {
                exif.getAttribute(key)
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            if (value.isEmpty() || value == "0") null
            else TagInfo(key, label, category, prettify(key, value))
        }
    }.getOrElse { error ->
        throw EngineException("No se pudieron leer los metadatos de «${file.name}»: ${error.message}")
    }

    private fun prettify(key: String, raw: String): String = when (key) {
        ExifInterface.TAG_ORIENTATION -> when (raw) {
            "1" -> "Normal"
            "3" -> "Rotada 180°"
            "6" -> "Rotada 90°"
            "8" -> "Rotada 270°"
            else -> "Valor $raw"
        }
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LONGITUDE -> formatCoordinate(raw, key)
        ExifInterface.TAG_FLASH -> when (raw) {
            "0" -> "No disparado"
            "1" -> "Sí"
            "16" -> "No (flash apagado)"
            else -> "Valor $raw"
        }
        else -> raw
    }

    private fun formatCoordinate(raw: String, key: String): String {
        val parts = raw.split(",")
        if (parts.size != 3) return raw
        val degrees = parts[0].trim().toDoubleOrNull()
        val minutes = parts[1].trim().toDoubleOrNull()
        val seconds = parts[2].trim().toDoubleOrNull()
        if (degrees == null || minutes == null || seconds == null) return raw
        val decimal = degrees + minutes / 60.0 + seconds / 3600.0
        val direction = when {
            key == ExifInterface.TAG_GPS_LATITUDE -> if (decimal >= 0) "N" else "S"
            else -> if (decimal >= 0) "E" else "O"
        }
        return String.format(java.util.Locale.US, "%.6f° %s", kotlin.math.abs(decimal), direction)
    }

    // ---------------------------------------------------------------- wiping

    /**
     * Copies each file, drops the requested metadata groups from the copy and
     * returns the cleaned files. Failures for specialist formats become
     * warnings — never silent.
     */
    suspend fun strip(
        files: List<WorkFile>,
        outDir: File,
        options: StripOptions,
        onProgress: ProgressListener = NoProgress,
    ): EngineOutcome {
        val outputs = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        val keys = when {
            options.removeAll -> TagsWithCategories.map { it.first }
            else -> buildList {
                if (options.removeGps) addAll(gpsKeys)
                if (options.removeCamera) addAll(cameraKeys)
                if (options.removeDate) addAll(dateKeys)
                if (options.removeAuthor) addAll(authorKeys)
            }
        }

        files.forEachIndexed { index, workFile ->
            checkCancelled()
            onProgress(index.toFloat() / files.size, "Limpiando ${workFile.displayName}")
            if (!canStrip(workFile.file)) {
                warnings += "«${workFile.displayName}»: el formato (.${workFile.extension}) no permite reescribir metadatos de forma segura en este dispositivo."
                return@forEachIndexed
            }
            runCatching {
                val extension = if (workFile.extension == "jpeg") "jpg" else workFile.extension
                val target = Storage.uniqueFile(outDir, workFile.displayName.substringBeforeLast('.'), extension)
                workFile.file.copyTo(target, overwrite = true)
                val exif = ExifInterface(target.absolutePath)
                for (key in keys) {
                    if (exif.getAttribute(key) != null) {
                        exif.setAttribute(key, null)
                    }
                }
                exif.saveAttributes()
                outputs += target
            }.onFailure { error ->
                warnings += "«${workFile.displayName}»: no se pudo limpiar (${error.message})"
            }
        }
        if (outputs.isEmpty()) {
            throw EngineException("No se pudo limpiar ningún archivo. Revisa los avisos.")
        }
        return EngineOutcome(
            outputs,
            warnings,
            stats = mapOf("Archivos limpios" to outputs.size.toString(), "Grupos eliminados" to removedGroupLabel(keys, options)),
        )
    }

    private fun removedGroupLabel(keys: List<String>, options: StripOptions): String {
        if (options.removeAll) return "todos"
        val groups = mutableListOf<String>()
        if (options.removeGps) groups += "GPS"
        if (options.removeCamera) groups += "cámara"
        if (options.removeDate) groups += "fecha"
        if (options.removeAuthor) groups += "autor"
        return groups.joinToString(", ")
    }

    // --------------------------------------------- tag catalogue (key, label, category)

    private val gpsKeys = setOf(
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_BEARING_REF, ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_VERSION_ID,
    )

    private val cameraKeys = setOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_SCENE_TYPE,
        ExifInterface.TAG_SENSING_METHOD, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_EXIF_VERSION, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_GAIN_CONTROL,
        ExifInterface.TAG_SATURATION, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_SHARPNESS, ExifInterface.TAG_SUBJECT_AREA,
        ExifInterface.TAG_SUBJECT_DISTANCE, ExifInterface.TAG_SUBJECT_DISTANCE_RANGE,
        ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL, ExifInterface.TAG_FOCAL_PLANE_X_RESOLUTION,
        ExifInterface.TAG_FOCAL_PLANE_Y_RESOLUTION, ExifInterface.TAG_FOCAL_PLANE_RESOLUTION_UNIT,
        ExifInterface.TAG_SUBJECT_LOCATION, ExifInterface.TAG_FLASH_ENERGY,
        ExifInterface.TAG_CUSTOM_RENDERED, ExifInterface.TAG_DEVICE_SETTING_DESCRIPTION,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_SPECTRAL_SENSITIVITY,
        ExifInterface.TAG_FILE_SOURCE, ExifInterface.TAG_CFA_PATTERN,
        ExifInterface.TAG_EXPOSURE_INDEX, ExifInterface.TAG_SENSING_METHOD,
    )

    private val dateKeys = setOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    )

    private val authorKeys = setOf(
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_XMP, ExifInterface.TAG_IMAGE_UNIQUE_ID,
    )

    private val technicalKeys = setOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH, ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION, ExifInterface.TAG_BITS_PER_SAMPLE,
        ExifInterface.TAG_COMPRESSION, ExifInterface.TAG_COLOR_SPACE,
        ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_X_RESOLUTION, ExifInterface.TAG_Y_RESOLUTION,
        ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION,
    )

    private fun Iterable<String>.addWith(labelPrefix: String, category: String, list: MutableList<Triple<String, String, String>>) {
        forEach { key -> list.add(Triple(key, "$labelPrefix ($key)", category)) }
    }

    // (key, label, category) — ordered, used for both reading and wiping.
    private val TagsWithCategories: List<Triple<String, String, String>> = buildList {
        gpsKeys.addWith("Coordenada", "Ubicación (GPS)", this)
        cameraKeys.addWith("Ajuste", "Cámara y disparo", this)
        dateKeys.addWith("Marca de tiempo", "Fecha y hora", this)
        authorKeys.addWith("Metadato", "Autor y edición", this)
        technicalKeys.addWith("Dato técnico", "Técnicos", this)
    }
}