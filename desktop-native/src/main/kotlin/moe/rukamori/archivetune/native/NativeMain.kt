package moe.rukamori.archivetune.native

import dev.nucleusframework.graalvm.GraalVmInitializer
import java.nio.file.Path
import moe.rukamori.archivetune.desktop.main as archiveTuneMain
import moe.rukamori.archivetune.desktop.platform.runDesktopLinuxPlatformSmoke

/**
 * GraalVM Native Image entry point for the desktop distribution.
 *
 * Nucleus needs to initialize its native-image substitutions before ArchiveTune touches AWT,
 * Compose, or Skiko. The production desktop entry point remains unchanged and is called directly
 * afterwards so the native build exercises the same application code as the regular JVM build.
 */
fun main(args: Array<String>) {
    GraalVmInitializer.initialize()

    // Native Image has no JVM application image from which ArchiveTune can infer its install root.
    // Point the existing VLC runtime locator at the directory containing the native executable so
    // it can use the same native/windows-x64/vlc layout as the normal Compose distribution.
    runCatching {
        ProcessHandle.current().info().command().orElse(null)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?.parent
            ?.let { System.setProperty("archivetune.app.dir", it.toString()) }
    }

    if (args.firstOrNull() == "--canvas-smoke") {
        runNativeCanvasSmoke(args.drop(1))
        return
    }

    if (args.firstOrNull() == "--linux-platform-smoke") {
        runDesktopLinuxPlatformSmoke(args.drop(1))
        return
    }

    archiveTuneMain()
}
