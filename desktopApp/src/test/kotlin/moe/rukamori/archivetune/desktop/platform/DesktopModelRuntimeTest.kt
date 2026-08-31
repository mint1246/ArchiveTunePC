package moe.rukamori.archivetune.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONObject

class DesktopModelRuntimeTest {
    @Test
    fun `new manifest keeps legacy stem root and exposes chord bundle`() {
        val root =
            JSONObject(
                java.nio.file.Files.readString(
                    java.nio.file.Path.of("..", "models", "manifest.json").toAbsolutePath().normalize(),
                ),
            )
        val stems = validateDesktopModelManifest(parseDesktopModelManifest(root, DesktopModels.STEMS), DesktopModels.STEMS)
        val chords = validateDesktopModelManifest(parseDesktopModelManifest(root, DesktopModels.CHORDS), DesktopModels.CHORDS)

        assertEquals(DesktopModels.STEMS.file, stems.file)
        assertEquals(DesktopModels.CHORDS.file, chords.file)
        assertEquals(DesktopModels.CHORDS.assets.size, chords.assets.size)
        assertEquals(23_896L, chords.assets.getValue("cqtPlan").size)
    }

    @Test
    fun `legacy one-model manifest still validates for stems`() {
        val root =
            JSONObject(
                """
                {
                  "version": "htdemucs-6s-1s-dml-v1",
                  "file": "htdemucs-6s-core-1s-dml.onnx",
                  "size": 110858418,
                  "sha256": "30ef61623d5d3a768f12dd87faadfdaaca26c5b8ef6a1c73f26b8c949b6e047c"
                }
                """.trimIndent(),
            )
        val entry = parseDesktopModelManifest(root, DesktopModels.STEMS)
        assertEquals(DesktopModels.STEMS.file, validateDesktopModelManifest(entry, DesktopModels.STEMS).file)
    }
}
