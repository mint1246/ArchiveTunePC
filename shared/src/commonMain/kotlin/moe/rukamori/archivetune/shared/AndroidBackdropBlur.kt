package moe.rukamori.archivetune.shared

/** Same clamped samples and integer channel division as Android DynamicAppBackdrop.boxBlur. */
internal fun androidBackdropBoxBlur(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
    require(width > 0 && height > 0 && input.size == width * height)
    if (radius <= 0) return input.copyOf()
    return IntArray(input.size) { index ->
        val x = index % width
        val y = index / width
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        var samples = 0
        for (dy in -radius..radius) {
            val row = (y + dy).coerceIn(0, height - 1) * width
            for (dx in -radius..radius) {
                val argb = input[row + (x + dx).coerceIn(0, width - 1)]
                alpha += argb ushr 24 and 255
                red += argb ushr 16 and 255
                green += argb ushr 8 and 255
                blue += argb and 255
                samples++
            }
        }
        ((alpha / samples) shl 24) or ((red / samples) shl 16) or
            ((green / samples) shl 8) or (blue / samples)
    }
}
