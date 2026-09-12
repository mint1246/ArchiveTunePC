package moe.rukamori.archivetune.chords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidChordModelStoreTest {
    @Test
    fun `manifest pins exact accelerated Android chord bundle`() {
        val parsed = AndroidChordModelStore.parseAndValidateManifest(validManifest())
        assertEquals(AndroidChordModelSpec.VERSION, parsed.version)
        assertEquals(AndroidChordModelSpec.SHA256, parsed.sha256)
        assertEquals(AndroidChordModelSpec.assets.associateBy { it.key }, parsed.assets)
    }

    @Test
    fun `manifest hash drift is rejected`() {
        val result = runCatching {
            AndroidChordModelStore.parseAndValidateManifest(
                validManifest().replace(AndroidChordModelSpec.SHA256, "0".repeat(64)),
            )
        }
        assertTrue(result.isFailure)
    }

    private fun validManifest() =
        """
        {
          "schemaVersion": 2,
          "models": {
            "chordsAndroid": {
              "version": "${AndroidChordModelSpec.VERSION}",
              "file": "${AndroidChordModelSpec.FILE}",
              "size": ${AndroidChordModelSpec.SIZE},
              "sha256": "${AndroidChordModelSpec.SHA256}",
              "sampleRate": 22050,
              "modelType": "chordmini-chordnet-2e1d",
              "sequenceLength": 108,
              "inputBins": 144,
              "chordClasses": 170,
              "assets": {
                "config": { "file": "chordmini-2e1d-v1.config.json", "size": 3009, "sha256": "1f26c11ebea51ec08f12e813eb213a729fa0ecc407ac7632dfdc7bad67e65aa4" },
                "cqtPlan": { "file": "chordmini-2e1d-v1.cqt-plan.bin", "size": 23896, "sha256": "c31f0a6fd2d582d753be6628b5daecdee58acba53cba93b2bc2b5c75dee2ba48" },
                "notice": { "file": "chordmini-2e1d-v1.NOTICE.md", "size": 1397, "sha256": "4b5c88848ec1bc339b3948fd145c34087d35be896978fc507a5784a2fd8716fe" }
              }
            }
          }
        }
        """.trimIndent()
}
