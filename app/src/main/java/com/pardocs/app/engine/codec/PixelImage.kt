package com.pardocs.app.engine.codec

import android.graphics.Bitmap

/** Pixel-level image interchange used by the codecs so they can be unit tested on the JVM. */
data class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    fun toBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    companion object {
        fun fromBitmap(bitmap: Bitmap): PixelImage {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return PixelImage(bitmap.width, bitmap.height, pixels)
        }
    }
}