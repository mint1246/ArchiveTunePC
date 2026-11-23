/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package moe.rukamori.archivetune.shared

import kotlin.math.abs
import kotlin.math.floor

internal data class AndroidPaletteSwatch(val rgb: Int, val population: Int)

/** AndroidX Palette 1.0.0 ColorCutQuantizer with its default filter. Mutates [pixels]. */
internal fun androidPaletteSwatches(pixels: IntArray, maxColors: Int = 48): List<AndroidPaletteSwatch> {
    require(maxColors > 0)
    val histogram = IntArray(1 shl 15)
    for (i in pixels.indices) {
        val p = pixels[i]
        val c = (((p ushr 16) and 255) shr 3 shl 10) or
            (((p ushr 8) and 255) shr 3 shl 5) or ((p and 255) shr 3)
        pixels[i] = c
        histogram[c]++
    }
    for (c in histogram.indices) if (histogram[c] > 0 && ignored(rgb(c))) histogram[c] = 0
    val colors = IntArray(histogram.count { it > 0 })
    var n = 0
    for (c in histogram.indices) if (histogram[c] > 0) colors[n++] = c
    if (colors.size <= maxColors) return colors.map { AndroidPaletteSwatch(rgb(it), histogram[it]) }

    class Box(var lower: Int, var upper: Int) {
        var minR = 0; var maxR = 0; var minG = 0; var maxG = 0; var minB = 0; var maxB = 0
        var population = 0
        fun fit() {
            minR = 31; minG = 31; minB = 31; maxR = 0; maxG = 0; maxB = 0; population = 0
            for (i in lower..upper) {
                val c = colors[i]; val r = c shr 10; val g = c shr 5 and 31; val b = c and 31
                minR = minOf(minR, r); maxR = maxOf(maxR, r)
                minG = minOf(minG, g); maxG = maxOf(maxG, g)
                minB = minOf(minB, b); maxB = maxOf(maxB, b)
                population += histogram[c]
            }
        }
        fun volume() = (maxR - minR + 1) * (maxG - minG + 1) * (maxB - minB + 1)
        fun split(): Box {
            val r = maxR - minR; val g = maxG - minG; val b = maxB - minB
            val dimension = if (r >= g && r >= b) 0 else if (g >= r && g >= b) 1 else 2
            fun swap() {
                if (dimension != 0) for (i in lower..upper) {
                    val c = colors[i]; val cr = c shr 10; val cg = c shr 5 and 31; val cb = c and 31
                    colors[i] = if (dimension == 1) (cg shl 10) or (cr shl 5) or cb else (cb shl 10) or (cg shl 5) or cr
                }
            }
            swap(); colors.sort(lower, upper + 1); swap()
            var count = 0; var point = lower
            for (i in lower..upper) {
                count += histogram[colors[i]]
                if (count >= population / 2) { point = minOf(upper - 1, i); break }
            }
            val other = Box(point + 1, upper); other.fit()
            upper = point; fit()
            return other
        }
        fun average(): AndroidPaletteSwatch {
            var rs = 0; var gs = 0; var bs = 0; var total = 0
            for (i in lower..upper) {
                val c = colors[i]; val p = histogram[c]; total += p
                rs += p * (c shr 10); gs += p * (c shr 5 and 31); bs += p * (c and 31)
            }
            fun mean(sum: Int) = floor(sum.toFloat() / total.toFloat() + 0.5f).toInt()
            return AndroidPaletteSwatch(rgb((mean(rs) shl 10) or (mean(gs) shl 5) or mean(bs)), total)
        }
    }
    // Java PriorityQueue's sift-up and sift-down, including its behavior for equal priorities.
    val heap = mutableListOf<Box>()
    fun offer(box: Box) {
        var i = heap.size; heap.add(box)
        while (i > 0) {
            val parent = (i - 1) ushr 1; val e = heap[parent]
            if (box.volume() <= e.volume()) break
            heap[i] = e; i = parent
        }
        heap[i] = box
    }
    fun poll(): Box {
        val result = heap[0]; val x = heap.removeAt(heap.lastIndex)
        if (heap.isNotEmpty()) {
            var i = 0; val half = heap.size ushr 1
            while (i < half) {
                var child = (i shl 1) + 1; var c = heap[child]; val right = child + 1
                if (right < heap.size && c.volume() < heap[right].volume()) { child = right; c = heap[right] }
                if (x.volume() >= c.volume()) break
                heap[i] = c; i = child
            }
            heap[i] = x
        }
        return result
    }
    val initial = Box(0, colors.lastIndex); initial.fit(); offer(initial)
    while (heap.size < maxColors) {
        val box = poll()
        if (box.upper <= box.lower) break
        offer(box.split()); offer(box)
    }
    return heap.map { it.average() }.filterNot { ignored(it.rgb) }
}

private fun rgb(c: Int): Int = (0xff shl 24) or ((c shr 10 and 31) shl 19) or
    ((c shr 5 and 31) shl 11) or ((c and 31) shl 3)

private fun ignored(rgb: Int): Boolean {
    val r = (rgb ushr 16 and 255) / 255f; val g = (rgb ushr 8 and 255) / 255f; val b = (rgb and 255) / 255f
    val max = maxOf(r, g, b); val min = minOf(r, g, b); val delta = max - min
    val l = (max + min) / 2f
    var h: Float; val s: Float
    if (max == min) { h = 0f; s = 0f } else {
        h = when (max) { r -> ((g - b) / delta) % 6f; g -> (b - r) / delta + 2f; else -> (r - g) / delta + 4f }
        s = delta / (1f - abs(2f * l - 1f))
    }
    h = (h * 60f) % 360f
    if (h < 0f) h += 360f
    return l >= 0.95f || l <= 0.05f || (h >= 10f && h <= 37f && s <= 0.82f)
}
