/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

/** Pure Kotlin Multiplatform SHA-1 digest used for YouTube SAPISIDHASH authentication headers. */
internal object KmpSha1 {
    fun hex(input: String): String {
        val bytes = input.encodeToByteArray()
        val bitLen = bytes.size.toLong() * 8L
        val paddedLen = ((bytes.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedLen)
        bytes.copyInto(padded)
        padded[bytes.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[paddedLen - 1 - i] = ((bitLen ushr (i * 8)) and 0xFFL).toByte()
        }

        var h0 = 0x67452301
        var h1 = -0x10325477 // 0xEFCDAB89
        var h2 = -0x67452302 // 0x98BADCFE
        var h3 = 0x10325476
        var h4 = -0x3c2d1e10 // 0xC3D2E1F0

        val w = IntArray(80)
        var offset = 0
        while (offset < paddedLen) {
            for (i in 0 until 16) {
                val base = offset + i * 4
                w[i] = ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) {
                val x = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
                w[i] = (x shl 1) or (x ushr 31)
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4

            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    i < 20 -> {
                        f = (b and c) or (b.inv() and d)
                        k = 0x5A827999
                    }
                    i < 40 -> {
                        f = b xor c xor d
                        k = 0x6ED9EBA1
                    }
                    i < 60 -> {
                        f = (b and c) or (b and d) or (c and d)
                        k = -0x70e44324 // 0x8F1BBCDC
                    }
                    else -> {
                        f = b xor c xor d
                        k = -0x359d3e2a // 0xCA62C1D6
                    }
                }
                val temp = ((a shl 5) or (a ushr 27)) + f + e + k + w[i]
                e = d
                d = c
                c = (b shl 30) or (b ushr 2)
                b = a
                a = temp
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            offset += 64
        }

        return buildString(40) {
            intArrayOf(h0, h1, h2, h3, h4).forEach { word ->
                for (shift in 28 downTo 0 step 4) {
                    append("0123456789abcdef"[(word ushr shift) and 0xF])
                }
            }
        }
    }

    fun sapisidHash(sapisid: String, origin: String, timestampSeconds: Long): String {
        val digest = hex("$timestampSeconds $sapisid $origin")
        return "SAPISIDHASH ${timestampSeconds}_$digest"
    }
}
