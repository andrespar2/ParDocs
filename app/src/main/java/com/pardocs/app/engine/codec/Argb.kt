package com.pardocs.app.engine.codec

/**
 * Pure-Kotlin ARGB packing equivalent to `android.graphics.Color`, so the
 * codecs can be exercised in plain JVM unit tests without the mockable jar.
 */
object Argb {
    fun rgb(r: Int, g: Int, b: Int): Int = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
}