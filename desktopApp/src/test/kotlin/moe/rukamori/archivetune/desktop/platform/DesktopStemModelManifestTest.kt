package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.json.JSONObject

class DesktopStemModelManifestTest {
    @Test
    fun `parses pinned six stem model manifest fields`() {
        val manifest = parseDesktopStemModelManifest(
            JSONObject(
                """
                {
                  "version": "htdemucs-6s-1s-dml-v1",
                  "file": "htdemucs-6s-core-1s-dml.onnx",
                  "size": 110858418,
                  "sha256": "30EF61623D5D3A768F12DD87FAADFDAACA26C5B8EF6A1C73F26B8C949B6E047C",
                  "stems": ["drums", "bass", "other", "vocals", "guitar", "piano"]
                }
                """.trimIndent(),
            ),
        )

        assertEquals("htdemucs-6s-1s-dml-v1", manifest.version)
        assertEquals("htdemucs-6s-core-1s-dml.onnx", manifest.file)
        assertEquals(110_858_418L, manifest.size)
        assertEquals(
            "30ef61623d5d3a768f12dd87faadfdaaca26c5b8ef6a1c73f26b8c949b6e047c",
            manifest.sha256,
        )
        assertEquals(manifest, validateDesktopStemModelManifest(manifest))
    }

    @Test
    fun `rejects manifest that does not match pinned model`() {
        val wrong = DesktopStemModelManifest(
            version = "htdemucs-6s-1s-dml-v1",
            file = "htdemucs-6s-core-1s-dml.onnx",
            size = 110_858_418L,
            sha256 = "0".repeat(64),
        )

        assertFailsWith<IllegalStateException> {
            validateDesktopStemModelManifest(wrong)
        }
    }
}
