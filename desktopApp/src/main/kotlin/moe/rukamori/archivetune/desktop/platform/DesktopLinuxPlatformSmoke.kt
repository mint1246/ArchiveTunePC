package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.linuxmedia.LinuxMediaControls
import moe.rukamori.archivetune.linuxmedia.LinuxMediaAvailability
import moe.rukamori.archivetune.linuxmedia.LinuxMemoryReclaimer
import moe.rukamori.archivetune.desktopauth.ChromiumBotGuardPoTokenProvider
import moe.rukamori.archivetune.desktopauth.secureSessionStore
import kotlin.system.exitProcess

/** Explicit diagnostic; uses a new directory, synthetic keyring data and a disposable browser. */
fun runDesktopLinuxPlatformSmoke(arguments: List<String>) {
    var success = false
    try {
        require(System.getProperty("os.name").startsWith("Linux", ignoreCase = true))
        require(arguments.size == 1) { "Expected a new temporary directory" }
        val root = Path.of(arguments.single()).toAbsolutePath().normalize()
        // Refuse existing directories so this diagnostic cannot select an existing account store.
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val mediaLogger = Logger.getLogger("moe.rukamori.archivetune.desktop.linuxmedia")
        mediaLogger.level = Level.FINE
        mediaLogger.addHandler(ConsoleHandler().apply { level = Level.FINE })
        LinuxMediaControls().use { controls ->
            controls.start()
            runBlocking {
                check(withTimeout(10_000L) {
                    controls.availability.first { it != LinuxMediaAvailability.NOT_STARTED }
                } == LinuxMediaAvailability.ACTIVE)
            }
            println("ARCHIVETUNE_PLATFORM_SMOKE mpris=ready bus=${controls.busName}")
            // Allows an independent D-Bus client to exercise exported methods and properties.
            Thread.sleep(5_000L)
        }
        val memoryReclaimer = LinuxMemoryReclaimer()
        check(memoryReclaimer.isSupported)
        println("ARCHIVETUNE_PLATFORM_SMOKE allocator_trim=ok released=${memoryReclaimer.trimCurrentProcess()}")
        val store = secureSessionStore(root.resolve("synthetic-keyring-session"))
        try {
            val synthetic = "archivetune-native-synthetic-smoke".toByteArray()
            store.write(synthetic)
            check(store.read()?.contentEquals(synthetic) == true)
        } finally { store.delete() }
        check(store.read() == null)
        println("ARCHIVETUNE_PLATFORM_SMOKE keyring=ok")

        checkNotNull(DesktopRuntime::class.java.getResourceAsStream("/icons/archivetune.png")).use {
            check(it.read() >= 0)
        }
        println("ARCHIVETUNE_PLATFORM_SMOKE icon=ok")

        val tokens = runBlocking {
            ChromiumBotGuardPoTokenProvider(tempRoot = root.resolve("browser")).mint(
                "aqz-KE-bpKQ", "archivetune-native-synthetic-smoke",
            )
        }
        check(tokens != null && tokens.playerToken.isNotBlank() && tokens.sessionToken.isNotBlank())
        // The generated proof values are deliberately never logged.
        println("ARCHIVETUNE_PLATFORM_SMOKE browser_http_websocket_botguard=ok")
        success = true
    } catch (failure: Throwable) {
        println("ARCHIVETUNE_PLATFORM_SMOKE failure=${failure.javaClass.simpleName}")
        failure.stackTrace.forEach { println("  at $it") }
    }
    exitProcess(if (success) 0 else 3)
}

/** JVM control run for the same diagnostic used by the native executable. */
fun main(args: Array<String>) = runDesktopLinuxPlatformSmoke(args.toList())
