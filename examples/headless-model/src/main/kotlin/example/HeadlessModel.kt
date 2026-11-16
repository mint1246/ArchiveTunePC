package example

import java.nio.file.Files
import java.nio.file.Path
import moe.rukamori.archivetune.desktop.platform.DesktopModels
import moe.rukamori.archivetune.desktop.platform.parseDesktopModelManifest
import moe.rukamori.archivetune.desktop.platform.validateDesktopModelManifest
import org.json.JSONObject

fun main(args: Array<String>) {
    require(args.size == 1) { "Pass the path to models/manifest.json" }
    val manifest = JSONObject(Files.readString(Path.of(args.single())))
    val model = validateDesktopModelManifest(parseDesktopModelManifest(manifest, DesktopModels.CHORDS), DesktopModels.CHORDS)
    println("Validated ${model.version}: ${model.assets.size} required assets")
}
