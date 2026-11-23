package moe.rukamori.archivetune.shared

import kotlin.math.*

/** AndroidX Palette's six default exclusive targets, in generation order. */
internal fun lyricsPaletteTargets(swatches: List<AndroidPaletteSwatch>): List<AndroidPaletteSwatch> {
    val dominant = swatches.maxByOrNull { it.population }
    val used = mutableSetOf<Int>()
    fun select(vibrant: Boolean, low: Float, target: Float, high: Float): AndroidPaletteSwatch? {
        var best: AndroidPaletteSwatch? = null
        var score = 0f
        for (s in swatches) {
            val r = (s.rgb shr 16 and 255) / 255f; val g = (s.rgb shr 8 and 255) / 255f; val b = (s.rgb and 255) / 255f
            val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
            val l = (max + min) / 2f
            val sat = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
            if (s.rgb in used || l !in low..high || (vibrant && sat < .35f) || (!vibrant && sat > .4f)) continue
            val candidate = .24f * (1f - abs(sat - if (vibrant) 1f else .3f)) + .52f * (1f - abs(l - target)) + .24f * s.population / (dominant?.population ?: 1)
            if (best == null || candidate > score) { best = s; score = candidate }
        }
        best?.let { used.add(it.rgb) }
        return best
    }
    val lightV = select(true, .55f, .74f, 1f)
    val v = select(true, .3f, .5f, .7f)
    val darkV = select(true, 0f, .26f, .45f)
    val lightM = select(false, .55f, .74f, 1f)
    val m = select(false, .3f, .5f, .7f)
    val darkM = select(false, 0f, .26f, .45f)
    return listOfNotNull(v, lightV, darkV, dominant, m, darkM, lightM).distinctBy { it.rgb }
}

internal fun lyricsGradient(swatches: List<AndroidPaletteSwatch>): List<Int> {
    val selected = lyricsPaletteTargets(swatches)
    val ranked = selected.sortedByDescending { s -> val h = androidHsv(s.rgb); s.population * if (h[1] > .3f && h[2] in .2f.. .9f) 1.3f else 1f }
    val colors = mutableListOf<Int>()
    fun similar(a: Int, b: Int): Boolean {
        val x = androidHsv(a); val y = androidHsv(b); val d = abs(x[0] - y[0])
        return (min(d, 360f - d) < 12f && abs(x[1] - y[1]) < .12f && abs(x[2] - y[2]) < .12f) ||
            listOf(0, 8, 16).all { abs((a shr it and 255) - (b shr it and 255)) < 28 }
    }
    for (s in ranked) {
        if (colors.none { similar(s.rgb, it) }) {
            val h = androidHsv(s.rgb)
            colors.add(androidHsvToArgb(h[0], (h[1] * if (h[1] > .3f) 1.25f else 1.05f).coerceAtMost(1f), (h[2] * 1.02f).coerceIn(.32f, .88f)))
        }
        if (colors.size >= 6) break
    }
    val population = selected.sumOf { it.population }.coerceAtLeast(1)
    val saturation = selected.sumOf { (androidHsv(it.rgb)[1] * it.population).toDouble() }.toFloat() / population
    val first = androidHsv(colors.firstOrNull() ?: 0xff000000.toInt())
    if (saturation < .22f || first[1] < .15f || first[2] < .08f) {
        val v = selected.maxByOrNull { it.population }?.let { androidHsv(it.rgb)[2] } ?: .1f
        return listOf((v * 1.2f).coerceIn(.06f,.40f), (v * .9f).coerceIn(.04f,.28f), (v * .6f).coerceIn(.02f,.16f)).map { androidHsvToArgb(0f, 0f, it) }
    }
    val bases = (colors.toList() + colors.first()).distinct()
    val shifts = listOf(25f,-25f,55f,-55f,120f,-120f,180f,150f,-150f)
    val values = listOf(.82f,.74f,.68f,.6f,.86f,.7f)
    var index = 0
    while (colors.size < 6 && index <= 40) {
        val h = androidHsv(bases[index % bases.size]); val shifted = androidHsvToArgb((h[0] + shifts[index % shifts.size] + 360f) % 360f,h[1],h[2]); val x = androidHsv(shifted)
        val derived = androidHsvToArgb(x[0], (max(x[1], .62f) * 1.08f).coerceIn(0f,1f), (x[2] * .85f + values[colors.size % values.size] * .15f).coerceIn(.38f,.9f))
        if (colors.none { similar(derived, it) }) colors.add(derived)
        index++
    }
    return colors
}
