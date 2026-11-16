package example

import java.nio.file.Path
import moe.rukamori.archivetune.chords.ChordEvent
import moe.rukamori.archivetune.chords.ChordTrack
import moe.rukamori.archivetune.chords.ChordTrackSource
import moe.rukamori.archivetune.desktop.platform.DesktopNeuralChordAnalyzer

fun main() {
    val track = ChordTrack(0, "Example", "Artist", null, ChordTrackSource.LOCAL_NEURAL, listOf(ChordEvent(0, 1000, "C")), 1000)
    check(track.alignedTo(1100).events.single().endMs == 1100L)
    DesktopNeuralChordAnalyzer(Path.of("cache"), Path.of("models"), "https://example.invalid/manifest.json", { _, _, _, _ ->
        error("Provide a real audio decoder before running analysis")
    })
    println("Loaded chord models and constructed the optional neural analyzer")
}
