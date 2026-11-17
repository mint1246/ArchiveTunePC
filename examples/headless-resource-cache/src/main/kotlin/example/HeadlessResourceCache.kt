package example

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator

fun main() = runBlocking {
    val directory = Files.createTempDirectory("archivetune-cache-example")
    val cache = DesktopResourceCoordinator(directory)
    cache.writePersistent("fixture:account", "example")
    check(cache.readPersistent("fixture:account")?.payload == "example")
    println("Restored cached content without application UI")
}
