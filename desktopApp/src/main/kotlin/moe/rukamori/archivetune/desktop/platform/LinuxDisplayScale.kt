/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import com.sun.jna.NativeLibrary
import kotlin.math.round

/**
 * Pins the AWT/Skiko UI scale on Linux before either toolkit reads it.
 *
 * Background: Nucleus 2.1.10 calls `dev.nucleusframework.hidpi.LinuxHiDpiKt.applyLinuxHiDpiScale()`
 * from `GraalVmInitializer.initialize()`. Its JNI bridge asks GNOME Mutter's DisplayConfig for the
 * scale of the *primary logical monitor* and writes that single value to `sun.java2d.uiScale` for
 * the whole process. On a mixed-DPI Wayland desktop that value is the compositor's logical scale of
 * the primary output (1.0 for a 3440x1440 DP-1 next to a 1920x1080 secondary at 1.25), while AWT and
 * Skiko render through Xwayland, whose root window is scaled to `Xft.dpi / 96` (2x once Mutter
 * composites a fractional output). Pinning the logical monitor scale therefore halves every Compose
 * dp.
 *
 * The JVM build never runs the Nucleus initializer, so Skiko's own Linux autodpi path
 * (`org.jetbrains.skiko.SetupKt.linuxGetSystemDpiScale`, which is also `Xft.dpi / 96`) stays in
 * charge. This helper presets exactly that value so Nucleus' guard (`sun.java2d.uiScale` already
 * set) skips the compositor value and the native build matches the JVM build, including multi-DPI
 * setups.
 *
 * Explicit user requests win over the detected value:
 * - `-Dsun.java2d.uiScale=<scale>` (also accepted by a native image binary at run time),
 * - `J2D_UISCALE` and `GDK_SCALE` environment variables,
 * - `-Dsun.java2d.uiScale.enabled=false` opts out of UI scaling entirely.
 *
 * `GDK_DPI_SCALE` is deliberately not treated as a UI scale: GTK applies it to text only, so using
 * it as a window scale would shrink or grow the whole UI by accident.
 */
object LinuxDisplayScale {
    internal const val UI_SCALE_PROPERTY = "sun.java2d.uiScale"
    internal const val UI_SCALE_ENABLED_PROPERTY = "sun.java2d.uiScale.enabled"
    internal const val SKIKO_AUTODPI_PROPERTY = "skiko.linux.autodpi"
    internal const val J2D_UISCALE_ENVIRONMENT = "J2D_UISCALE"
    internal const val GDK_SCALE_ENVIRONMENT = "GDK_SCALE"
    internal const val DEBUG_PROPERTY = "archivetune.uiScale.debug"
    internal const val DEBUG_ENVIRONMENT = "ARCHIVETUNE_UI_SCALE_DEBUG"

    /** X resources are expressed in dots per inch with 96 dpi as the 1x baseline, like AWT/Skiko. */
    internal const val BASELINE_DPI = 96.0

    /**
     * Applies the resolved scale. Must run before Nucleus' `GraalVmInitializer.initialize()` (which
     * applies the compositor scale) and before AWT/Skiko read `sun.java2d.uiScale`.
     */
    fun apply() {
        if (!isLinuxHost()) return

        if (isUiScaleExplicitlyDisabled()) {
            // Nucleus would re-enable scaling when the property is absent, so pin it (its own guard
            // then skips) and keep Skiko's autodpi from overwriting/rescaling.
            if (System.getProperty(UI_SCALE_PROPERTY) == null) {
                System.setProperty(UI_SCALE_PROPERTY, "1")
            }
            System.setProperty(SKIKO_AUTODPI_PROPERTY, "false")
            debug("UI scaling disabled by $UI_SCALE_ENABLED_PROPERTY")
            return
        }

        val decision =
            decideLinuxUiScale(
                uiScaleProperty = System.getProperty(UI_SCALE_PROPERTY),
                j2dUiScaleEnvironment = System.getenv(J2D_UISCALE_ENVIRONMENT),
                gdkScaleEnvironment = System.getenv(GDK_SCALE_ENVIRONMENT),
                xftDpi = readXftDpi(),
            )
        val scale = decision.scale
        if (scale == null) {
            // Nothing authoritative to pin: leave the existing Skiko/Nucleus fallbacks untouched.
            debug("no explicit or X11-derived scale; leaving AWT/Skiko detection in charge")
            return
        }

        System.setProperty(UI_SCALE_ENABLED_PROPERTY, "true")
        if (decision.source != LinuxScaleSource.PROPERTY) {
            // Keep a user-supplied Java2D value (including "2x", "200%" and "192dpi" forms) verbatim;
            // only values harvested from the environment or X resources have to be written here.
            System.setProperty(UI_SCALE_PROPERTY, formatLinuxUiScale(scale))
        }
        if (decision.explicit) {
            // Skiko's Setup.init overwrites sun.java2d.uiScale unconditionally; keep the user's value.
            System.setProperty(SKIKO_AUTODPI_PROPERTY, "false")
        }
        debug("scale=${formatLinuxUiScale(scale)} source=${decision.source} explicit=${decision.explicit}")
    }

    private fun isLinuxHost(): Boolean = System.getProperty("os.name").orEmpty().contains("Linux")

    private fun isUiScaleExplicitlyDisabled(): Boolean =
        System.getProperty(UI_SCALE_ENABLED_PROPERTY)?.trim()?.equals("false", ignoreCase = true) == true

    /**
     * Reads `Xft.dpi` from the X server's `RESOURCE_MANAGER` string.
     *
     * Implemented with raw `libX11` functions through JNA (`XOpenDisplay`, `XResourceManagerString`,
     * `XCloseDisplay`) so no `xprop`/`xrdb` runtime dependency is required. Returns `null` when the
     * display is unreachable or does not publish the resource, in which case the caller keeps the
     * toolkit defaults.
     */
    internal fun readXftDpi(): Double? {
        val displayName = System.getenv("DISPLAY")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val x11 = NativeLibrary.getInstance("X11")
            val display =
                x11.getFunction("XOpenDisplay").invokePointer(arrayOf(displayName))
                    ?: return@runCatching null
            try {
                val resources =
                    x11.getFunction("XResourceManagerString")
                        .invokePointer(arrayOf(display))
                        ?.getString(0)
                parseXftDpi(resources)
            } finally {
                runCatching { x11.getFunction("XCloseDisplay").invokeInt(arrayOf(display)) }
            }
        }.getOrNull()
    }

    private fun debug(message: String) {
        val enabled =
            System.getProperty(DEBUG_PROPERTY)?.equals("true", ignoreCase = true) == true ||
                System.getenv(DEBUG_ENVIRONMENT) == "1"
        if (enabled) System.err.println("[archivetune] LinuxDisplayScale: " + message)
    }
}

internal enum class LinuxScaleSource {
    PROPERTY,
    J2D_UISCALE_ENVIRONMENT,
    GDK_SCALE_ENVIRONMENT,
    XFT_DPI,
}

internal data class LinuxScaleDecision(
    val scale: Double?,
    val source: LinuxScaleSource?,
    val explicit: Boolean,
)

/**
 * Resolves the UI scale without touching any process state so it stays unit-testable.
 *
 * Precedence: explicit `sun.java2d.uiScale` property, `J2D_UISCALE`, `GDK_SCALE`, then `Xft.dpi`
 * converted with a 96 dpi baseline.
 */
internal fun decideLinuxUiScale(
    uiScaleProperty: String?,
    j2dUiScaleEnvironment: String?,
    gdkScaleEnvironment: String?,
    xftDpi: Double?,
): LinuxScaleDecision {
    parseUiScale(uiScaleProperty)?.let {
        return LinuxScaleDecision(it, LinuxScaleSource.PROPERTY, explicit = true)
    }
    parseUiScale(j2dUiScaleEnvironment)?.let {
        return LinuxScaleDecision(it, LinuxScaleSource.J2D_UISCALE_ENVIRONMENT, explicit = true)
    }
    parseUiScale(gdkScaleEnvironment)?.let {
        return LinuxScaleDecision(it, LinuxScaleSource.GDK_SCALE_ENVIRONMENT, explicit = true)
    }
    if (xftDpi != null && xftDpi.isFinite() && xftDpi > 0.0) {
        val xftScale = xftDpi / LinuxDisplayScale.BASELINE_DPI
        if (isUsableUiScale(xftScale)) {
            return LinuxScaleDecision(xftScale, LinuxScaleSource.XFT_DPI, explicit = false)
        }
    }
    return LinuxScaleDecision(null, null, explicit = false)
}

private const val MIN_UI_SCALE = 0.5
private const val MAX_UI_SCALE = 8.0

/**
 * Parses a toolkit scale request; blank, malformed and out-of-range values are ignored.
 *
 * Java2D itself accepts plain numbers as well as `"2x"`, `"200%"` and `"192dpi"` forms, so a user
 * that spells the same request in one of those forms must not have it replaced by detection.
 */
internal fun parseUiScale(raw: String?): Double? {
    val text = raw?.trim()?.lowercase().orEmpty()
    if (text.isEmpty()) return null
    val scale =
        when {
            text.endsWith("%") -> text.dropLast(1).trim().toDoubleOrNull()?.div(100.0)
            text.endsWith("dpi") ->
                text.dropLast(3).trim().toDoubleOrNull()?.div(LinuxDisplayScale.BASELINE_DPI)
            text.endsWith("x") -> text.dropLast(1).trim().toDoubleOrNull()
            else -> text.toDoubleOrNull()
        } ?: return null
    return scale.takeIf(::isUsableUiScale)
}

private fun isUsableUiScale(scale: Double): Boolean =
    scale.isFinite() && scale >= MIN_UI_SCALE && scale <= MAX_UI_SCALE

/**
 * Formats a scale for `sun.java2d.uiScale`. Integral values stay integral (`2` instead of `2.0`)
 * and fractional values are rounded to three decimals to drop binary-noise digits.
 */
internal fun formatLinuxUiScale(scale: Double): String {
    val rounded = round(scale * 1000.0) / 1000.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

/**
 * Extracts `Xft.dpi` from an X resource manager string such as `"Xft.dpi:\t192\nXft.rgba:\tnone"`.
 */
internal fun parseXftDpi(resourceManager: String?): Double? {
    if (resourceManager.isNullOrBlank()) return null
    for (line in resourceManager.lineSequence()) {
        val separator = line.indexOf(':')
        if (separator <= 0) continue
        if (line.substring(0, separator).trim() != "Xft.dpi") continue
        return line.substring(separator + 1).trim().toDoubleOrNull()
    }
    return null
}
