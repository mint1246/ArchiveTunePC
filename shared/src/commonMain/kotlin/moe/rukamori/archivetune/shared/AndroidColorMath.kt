/*
 * Copyright 2006 The Android Open Source Project
 * Kotlin adaptation of Skia src/core/SkColor.cpp's byte RGB/HSV conversion.
 * BSD license: ../com/materialkolor/SKIA-LICENSE (included in this source tree).
 */
package moe.rukamori.archivetune.shared

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal fun androidHsv(argb: Int): FloatArray {
    val r = argb ushr 16 and 255
    val g = argb ushr 8 and 255
    val b = argb and 255
    val highest = max(r, max(g, b))
    val lowest = min(r, min(g, b))
    val delta = highest - lowest
    if (delta == 0) return floatArrayOf(0f, 0f, highest / 255f)
    var hue = when (highest) {
        r -> (g - b).toFloat() / delta
        g -> 2f + (b - r).toFloat() / delta
        else -> 4f + (r - g).toFloat() / delta
    } * 60f
    if (hue < 0f) hue += 360f
    return floatArrayOf(hue, delta.toFloat() / highest, highest / 255f)
}

internal fun androidHsvToArgb(hue: Float, saturation: Float, brightness: Float): Int {
    val s = saturation.coerceIn(0f, 1f)
    val v = brightness.coerceIn(0f, 1f)
    fun round(value: Float) = floor(value + .5f).toInt()
    val value = round(v * 255)
    fun rgb(r: Int, g: Int, b: Int) = 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
    if (abs(s) <= 1f / 4096f) return rgb(value, value, value)
    val hx = if (hue < 0f || hue >= 360f) 0f else hue / 60f
    val sector = floor(hx).toInt()
    val fraction = hx - sector
    val p = round((1f - s) * v * 255)
    val q = round((1f - s * fraction) * v * 255)
    val t = round((1f - s * (1f - fraction)) * v * 255)
    return when (sector) {
        0 -> rgb(value, t, p)
        1 -> rgb(q, value, p)
        2 -> rgb(p, value, t)
        3 -> rgb(p, q, value)
        4 -> rgb(t, p, value)
        else -> rgb(value, p, q)
    }
}
