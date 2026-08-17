/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** The native files libVLC needs before VLCJ can create a [uk.co.caprica.vlcj.factory.MediaPlayerFactory]. */
data class VlcRuntimeLayout(
    val root: Path,
    val libVlc: Path,
    val libVlcCore: Path,
    val plugins: Path,
)

data class VlcRuntimeConfiguration(
    val directory: Path? = null,
    val applicationDirectory: Path? = null,
)

/**
 * Locates a self-contained libVLC tree. The packaged application path is preferred over a machine
 * VLC install; environment/property overrides exist for development and diagnostics only.
 */
object VlcRuntimeLocator {
    fun find(configuration: VlcRuntimeConfiguration = VlcRuntimeConfiguration()): VlcRuntimeLayout? {
        val candidates = buildList {
            configuration.directory?.let(::add)
            System.getProperty("archivetune.vlc.dir")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let(::add)
            System.getenv("ARCHIVETUNE_VLC_DIR")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let(::add)

            val app = configuration.applicationDirectory
                ?: System.getProperty("archivetune.app.dir")
                    ?.takeIf(String::isNotBlank)
                    ?.let(Path::of)
            if (app != null) {
                add(app.resolve("native").resolve("windows-x64").resolve("vlc"))
                add(app.resolve("native").resolve("vlc"))
                add(app.resolve("vlc"))
            }

            val codeSource = runCatching {
                Path.of(VlcRuntimeLocator::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull()
            codeSource?.let { jarOrClasses ->
                val base = if (Files.isRegularFile(jarOrClasses)) jarOrClasses.parent else jarOrClasses
                add(base.resolve("resources/native/windows-x64/vlc"))
                add(base.resolve("resources/native/vlc"))
                add(base.resolve("vlc"))
            }

            System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.resolve("ArchiveTune")
                ?.resolve("runtime")
                ?.let(::add)

            if (isWindows()) {
                add(Path.of("C:\\Program Files\\VideoLAN\\VLC"))
                add(Path.of("C:\\Program Files (x86)\\VideoLAN\\VLC"))
            }
        }.map { it.toAbsolutePath().normalize() }.distinct()

        candidates.forEach { candidate ->
            if (isLayoutRoot(candidate)) return layout(candidate)
            if (Files.isDirectory(candidate)) {
                runCatching {
                    Files.list(candidate).use { stream ->
                        stream.filter(Files::isDirectory).filter(::isLayoutRoot).findFirst().orElse(null)
                    }
                }.getOrNull()?.let { return layout(it) }
            }
        }
        return null
    }

    /**
     * Must run before the first VLCJ/libVLC class initializes native bindings. It is intentionally
     * idempotent so a single application-scoped backend can call it during lazy initialization.
     */
    fun prepareNative(layout: VlcRuntimeLayout) {
        val root = layout.root.toString()
        System.setProperty("jna.library.path", root)
        System.setProperty("java.library.path", root)
        if (isWindows()) {
            val path = System.getenv("PATH").orEmpty()
            if (!path.split(';').any { it.equals(root, ignoreCase = true) }) {
                // VLC's plugin DLLs may depend on sibling runtime DLLs. JNA's library path is not
                // sufficient for every Windows loader path, so keep the directory on PATH too.
                System.setProperty("archivetune.vlc.path", "$root;$path")
            }
        }
    }

    private fun isLayoutRoot(path: Path): Boolean =
        Files.isRegularFile(path.resolve("libvlc.dll")) &&
            Files.isRegularFile(path.resolve("libvlccore.dll")) &&
            Files.isDirectory(path.resolve("plugins"))

    private fun layout(root: Path): VlcRuntimeLayout =
        VlcRuntimeLayout(
            root = root,
            libVlc = root.resolve("libvlc.dll"),
            libVlcCore = root.resolve("libvlccore.dll"),
            plugins = root.resolve("plugins"),
        )

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
}

internal fun VlcRuntimeLayout.mediaPlayerFactoryOptions(): Array<String> =
    arrayOf(
        "--no-video",
        "--plugin-path=${plugins.toAbsolutePath().normalize()}",
        "--quiet",
    )
