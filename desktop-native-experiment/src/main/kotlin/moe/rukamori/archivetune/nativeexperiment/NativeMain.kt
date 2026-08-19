package moe.rukamori.archivetune.nativeexperiment

import dev.nucleusframework.graalvm.GraalVmInitializer
import java.nio.file.Path
import moe.rukamori.archivetune.desktop.main as archiveTuneMain

/**
 * Experimental GraalVM Native Image entry point.
 *
 * Nucleus needs to initialize its native-image substitutions before ArchiveTune touches AWT,
 * Compose, or Skiko. The production desktop entry point remains unchanged and is called directly
 * afterwards so this experiment exercises the real application rather than a reduced test shell.
 */
fun main() {
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

    archiveTuneMain()
}