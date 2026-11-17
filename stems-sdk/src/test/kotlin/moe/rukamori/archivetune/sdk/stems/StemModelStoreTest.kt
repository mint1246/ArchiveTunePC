/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StemModelStoreTest {
    @Test
    fun manifestContract_prefersAndPinsAndroidStemAsset() {
        val parsed =
            StemModelStore.parseStemManifest(
                """
                {
                  "models": {
                    "stems": {
                      "version": "desktop-old",
                      "file": "desktop.onnx",
                      "size": 1,
                      "sha256": "${"0".repeat(64)}"
                    },
                    "stemsAndroid": {
                      "version": "${AndroidStemModelSpec.VERSION}",
                      "file": "${AndroidStemModelSpec.FILE}",
                      "size": ${AndroidStemModelSpec.SIZE},
                      "sha256": "${AndroidStemModelSpec.SHA256.uppercase()}"
                    }
                  }
                }
                """.trimIndent(),
            )

        assertEquals(AndroidStemModelSpec.VERSION, parsed.version)
        assertEquals(AndroidStemModelSpec.FILE, parsed.file)
        assertEquals(AndroidStemModelSpec.SIZE, parsed.size)
        assertEquals(AndroidStemModelSpec.SHA256, parsed.sha256)
        assertEquals(parsed, StemModelStore.validateStemManifest(parsed))
    }

    @Test
    fun manifestMismatch_isRejectedBeforeDownload() {
        val wrong =
            StemModelAsset(
                version = AndroidStemModelSpec.VERSION,
                file = AndroidStemModelSpec.FILE,
                size = AndroidStemModelSpec.SIZE,
                sha256 = "0".repeat(64),
            )
        val failure = runCatching { StemModelStore.validateStemManifest(wrong) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun modelDownload_resolvesBesideManifest() {
        assertEquals(
            "https://models.example/models/${AndroidStemModelSpec.FILE}",
            StemModelStore.resolveRelativeUrl(
                "https://models.example/models/manifest.json",
                AndroidStemModelSpec.FILE,
            ),
        )
    }
}
