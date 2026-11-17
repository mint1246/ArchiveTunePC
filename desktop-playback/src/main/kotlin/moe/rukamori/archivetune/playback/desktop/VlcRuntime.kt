/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.desktop

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal enum class VlcRuntimeOs {
    WINDOWS, LINUX, OTHER;

    companion object {
        fun current(): VlcRuntimeOs = fromOsName(System.getProperty("os.name", ""))

        internal fun fromOsName(name: String): VlcRuntimeOs = when {
            name.lowercase(Locale.ROOT).startsWith("windows") -> WINDOWS
            name.lowercase(Locale.ROOT).contains("linux") -> LINUX
            else -> OTHER
        }
    }
}

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
 * Resolves packaged libVLC runtimes or the distribution's installed libVLC and plugin tree.
 * Linux distributions commonly keep the shared objects in /usr/lib and plugins in /usr/lib/vlc/plugins.
 * Overrides remain preferred so a packaged runtime can take precedence over the system version.
 */
object VlcRuntimeLocator {
    fun find(configuration: VlcRuntimeConfiguration = VlcRuntimeConfiguration()): VlcRuntimeLayout? =
        find(configuration, VlcRuntimeOs.current())

    internal fun find(
        configuration: VlcRuntimeConfiguration,
        os: VlcRuntimeOs,
    ): VlcRuntimeLayout? {
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
                if (os == VlcRuntimeOs.WINDOWS) add(app.resolve("native/windows-x64/vlc"))
                if (os == VlcRuntimeOs.LINUX) add(app.resolve("native/linux-x64/vlc"))
                add(app.resolve("native/vlc"))
                add(app.resolve("vlc"))
            }

            val codeSource = runCatching {
                Path.of(VlcRuntimeLocator::class.java.protectionDomain.codeSource.location.toURI())
            }.getOrNull()
            codeSource?.let { jarOrClasses ->
                val base = if (Files.isRegularFile(jarOrClasses)) jarOrClasses.parent else jarOrClasses
                if (os == VlcRuntimeOs.WINDOWS) add(base.resolve("resources/native/windows-x64/vlc"))
                if (os == VlcRuntimeOs.LINUX) add(base.resolve("resources/native/linux-x64/vlc"))
                add(base.resolve("resources/native/vlc"))
                add(base.resolve("vlc"))
            }

            when (os) {
                VlcRuntimeOs.WINDOWS -> {
                    System.getenv("LOCALAPPDATA")
                        ?.takeIf(String::isNotBlank)
                        ?.let(Path::of)
                        ?.resolve("ArchiveTune/runtime")
                        ?.let(::add)
                    System.getenv("LOCALAPPDATA")
                        ?.takeIf(String::isNotBlank)
                        ?.let(Path::of)
                        ?.resolve("ArchiveTuneSpikes/runtime")
                        ?.let(::add)
                    add(Path.of("C:\\Program Files\\VideoLAN\\VLC"))
                    add(Path.of("C:\\Program Files (x86)\\VideoLAN\\VLC"))
                }
                VlcRuntimeOs.LINUX -> {
                    // Both Arch/CachyOS and Debian-style multiarch libVLC layouts.
                    add(Path.of("/usr/local/lib"))
                    add(Path.of("/usr/local/lib64"))
                    add(Path.of("/usr/local/lib/x86_64-linux-gnu"))
                    add(Path.of("/usr/lib"))
                    add(Path.of("/usr/lib64"))
                    add(Path.of("/usr/lib/x86_64-linux-gnu"))
                }
                VlcRuntimeOs.OTHER -> Unit
            }
        }.map { it.toAbsolutePath().normalize() }.distinct()

        candidates.forEach { candidate ->
            inspectDirectory(candidate, os)?.let { return it }
            if (Files.isDirectory(candidate)) {
                runCatching {
                    Files.list(candidate).use { stream ->
                        stream.filter(Files::isDirectory)
                            .map { inspectDirectory(it, os) }
                            .filter { it != null }
                            .findFirst().orElse(null)
                    }
                }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    /**
     * The JVM can find libvlc by its usual JNA library name once its containing directory is
     * registered. Calling this before MediaPlayerFactory also works for system-installed VLC.
     */
    fun prepareNative(layout: VlcRuntimeLayout) {
        val root = layout.root.toString()
        System.setProperty("jna.library.path", root)
        System.setProperty("java.library.path", root)
        NativeLibrary.addSearchPath("libvlc", root)
        NativeLibrary.addSearchPath("libvlccore", root)
    }

    /** Pure filesystem check, shared with platform layout tests. */
    internal fun inspectDirectory(directory: Path, os: VlcRuntimeOs): VlcRuntimeLayout? {
        val root = directory.toAbsolutePath().normalize()
        val (libVlcNames, libCoreNames) = when (os) {
            VlcRuntimeOs.WINDOWS -> listOf("libvlc.dll") to listOf("libvlccore.dll")
            VlcRuntimeOs.LINUX -> listOf("libvlc.so", "libvlc.so.5") to
                listOf("libvlccore.so", "libvlccore.so.9")
            VlcRuntimeOs.OTHER -> return null
        }
        val libVlc = libVlcNames.asSequence().map(root::resolve).firstOrNull(Files::isRegularFile) ?: return null
        val libCore = libCoreNames.asSequence().map(root::resolve).firstOrNull(Files::isRegularFile) ?: return null
        val plugins = buildList {
            add(root.resolve("plugins"))
            if (os == VlcRuntimeOs.LINUX) add(root.resolve("vlc/plugins"))
        }.firstOrNull(Files::isDirectory) ?: return null
        return VlcRuntimeLayout(root = root, libVlc = libVlc, libVlcCore = libCore, plugins = plugins)
    }
}

internal fun VlcRuntimeLayout.mediaPlayerFactoryOptions(
    os: VlcRuntimeOs = VlcRuntimeOs.current(),
): Array<String> = buildList {
    add("--no-video")
    add("--quiet")
    if (os == VlcRuntimeOs.WINDOWS) {
        // VLC 3's Windows mmdevice backend shares one session volume across crossfade players.
        // DirectSound gives the players independent buffers and volume controls on Windows.
        add("--aout=directsound")
    }
    // On Linux, let libVLC select the system output (PulseAudio/PipeWire or ALSA).
}.toTypedArray()

internal object VlcPitchBridge {
    // This bridge reads a private VLC 3 Windows x64 struct field at offset 0x68. Linux builds
    // have a different ABI; dereferencing that offset would risk a native process crash.
    // A portable pitch implementation must first replace this Windows-only native bridge.
    internal fun isSupportedOnCurrentPlatform(): Boolean = VlcRuntimeOs.current() == VlcRuntimeOs.WINDOWS

    private const val VLC_VAR_FLOAT = 0x0050
    private const val VLC_VAR_DOINHERIT = 0x8000
    private const val MEDIA_PLAYER_INPUT_RESOURCE_OFFSET = 0x68L
    private val core by lazy { NativeLibrary.getInstance("libvlccore") }
    private val inputResourceHoldAout by lazy { core.getFunction("input_resource_HoldAout") }
    private val objectRelease by lazy { core.getFunction("vlc_object_release") }
    private val varCreate by lazy { core.getFunction("var_Create") }
    private val varGet by lazy { core.getFunction("var_Get") }
    private val varSet by lazy { core.getFunction("var_Set") }
    private val crtFree by lazy { NativeLibrary.getInstance("msvcrt").getFunction("free") }

    /** Apply pitch to the *actual* active audio-output object used by VLC 3.0.21. */
    fun applyToActiveAudioOutput(mediaPlayer: uk.co.caprica.vlcj.player.base.MediaPlayer, semitones: Float): Boolean {
        if (!isSupportedOnCurrentPlatform()) return false
        val mediaPlayerPointer = mediaPlayer.mediaPlayerInstance().pointer
        val inputResource = mediaPlayerPointer.getPointer(MEDIA_PLAYER_INPUT_RESOURCE_OFFSET) ?: return false
        val aout = inputResourceHoldAout.invokePointer(arrayOf<Any?>(inputResource)) ?: return false
        try {
            val enabled = kotlin.math.abs(semitones) >= 0.001f
            // Seed the inherited pitch value before constructing scaletempo_pitch. VLC's filter
            // reads this during OpenPitch(); setting it only after adding the filter can leave a
            // newly-created filter briefly initialized from the old/default tuning.
            val pitchName = nativeString("pitch-shift")
            varCreate.invokeInt(arrayOf<Any?>(aout, pitchName, VLC_VAR_FLOAT or VLC_VAR_DOINHERIT))
            val pitchBits = java.lang.Float.floatToRawIntBits(semitones).toLong() and 0xffff_ffffL
            varSet.invokeInt(arrayOf<Any?>(aout, pitchName, pitchBits))

            val currentFilters = getStringVariable(aout, "audio-filter")
                .orEmpty()
                .split(':')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toMutableList()
            val hadPitchFilter = currentFilters.removeAll { it == "scaletempo_pitch" }
            if (enabled) currentFilters.add("scaletempo_pitch")
            if (hadPitchFilter != enabled) {
                setStringVariable(aout, "audio-filter", currentFilters.distinct().joinToString(":"))
                // Reassert after the pipeline rebuild too. This also covers VLC versions where
                // the first inherited read races the dynamic audio-filter reconfiguration.
                if (enabled) varSet.invokeInt(arrayOf<Any?>(aout, pitchName, pitchBits))
            }
            return true
        } finally {
            objectRelease.invokeVoid(arrayOf<Any?>(aout))
        }
    }

    private fun getStringVariable(objectPointer: com.sun.jna.Pointer, name: String): String? {
        val nativeName = nativeString(name)
        val value = Memory(java.lang.Long.BYTES.toLong())
        if (varGet.invokeInt(arrayOf<Any?>(objectPointer, nativeName, value)) != 0) return null
        val stringPointer = value.getPointer(0) ?: return ""
        return try {
            stringPointer.getString(0, Charsets.UTF_8.name())
        } finally {
            crtFree.invokeVoid(arrayOf<Any?>(stringPointer))
        }
    }

    private fun setStringVariable(objectPointer: com.sun.jna.Pointer, name: String, value: String) {
        val nativeName = nativeString(name)
        val nativeValue = nativeString(value)
        // vlc_value_t is an 8-byte union on x64. For a string member its by-value bits are exactly
        // the char* value, so passing the pointer directly avoids JNA Structure/Union reflection.
        varSet.invokeInt(arrayOf<Any?>(objectPointer, nativeName, nativeValue))
    }

    private fun nativeString(value: String): Memory {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return Memory(bytes.size.toLong() + 1L).apply {
            write(0, bytes, 0, bytes.size)
            setByte(bytes.size.toLong(), 0)
        }
    }
}
