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
            System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.resolve("ArchiveTuneSpikes")
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
        // VLCJ resolves libvlc through JNA. Register both DLL names explicitly so packaged
        // applications do not depend on a developer machine's PATH or registry state.
        NativeLibrary.addSearchPath("libvlc", root)
        NativeLibrary.addSearchPath("libvlccore", root)
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
        "--quiet",
        // VLC 3's default Windows mmdevice output controls ISimpleAudioVolume, so two players in
        // the same process share one session volume and fight each other during a crossfade.
        // DirectSound gives each VLC player its own secondary sound buffer; DirectSound performs
        // the actual mix and per-buffer volume changes do not modulate the other player.
        "--aout=directsound",
    )

internal object VlcPitchBridge {
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
