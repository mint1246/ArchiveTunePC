/*
 * ArchiveTune (2026)
 * Ac Rukamori – github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modelruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidModelStoreTest {
    @Test
    fun desktopManifestContract_parsesAndPinsExactStemAsset() {
        val parsed =
            AndroidModelStore.parseStemManifest(
                """
                {
                  "models": {
                    "stems": {
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
        assertEquals(parsed, AndroidModelStore.validateStemManifest(parsed))
    }

    @Test
    fun manifestMismatch_isRejectedBeforeDownload() {
        val wrong =
            AndroidModelAsset(
                version = AndroidStemModelSpec.VERSION,
                file = AndroidStemModelSpec.FILE,
                size = AndroidStemModelSpec.SIZE,
                sha256 = "0".repeat(64),
            )
        val failure = runCatching { AndroidModelStore.validateStemManifest(wrong) }
        assertTrue(failure.isFailure)
    }
}
