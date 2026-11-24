/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.resources.Res
import moe.rukamori.archivetune.shared.resources.poppins
import org.jetbrains.compose.resources.Font

/** Injectable schemes allow a platform or preview to supply its own palette. */
internal data class ArchiveTuneColorSchemes(val light: ColorScheme, val dark: ColorScheme)
internal val LocalArchiveTuneColorSchemes = staticCompositionLocalOf {
    ArchiveTuneColorSchemes(AndroidReferenceColors.light, AndroidReferenceColors.dark)
}

/** Android's Poppins typography and expressive corner sizes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ArchiveTuneTheme(
    theme: MusicThemeChoice,
    artworkUrl: String? = null,
    isPlaying: Boolean = false,
    pureBlack: Boolean = false,
    dynamicTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        MusicThemeChoice.SYSTEM -> isSystemInDarkTheme()
        MusicThemeChoice.DARK -> true
        MusicThemeChoice.LIGHT -> false
    }
    val family = FontFamily(Font(Res.font.poppins))
    val base = Typography()
    val typography = Typography(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
    val schemes = LocalArchiveTuneColorSchemes.current
    val effectiveArtwork = if (dynamicTheme) artworkUrl else null
    val artworkPalette by rememberArtworkThemePalette(effectiveArtwork)
    val baseColors = remember(artworkPalette, dark, schemes, pureBlack) {
        val scheme = artworkPalette?.let { exactPaletteColorScheme(it, dark) } ?: if (dark) schemes.dark else schemes.light
        if (dark && pureBlack) {
            scheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceDim = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF0A0A0A),
                surfaceContainer = Color(0xFF121212),
                surfaceContainerHigh = Color(0xFF1A1A1A),
                surfaceContainerHighest = Color(0xFF222222),
            )
        } else {
            scheme
        }
    }
    val colors = remember(baseColors, effectiveArtwork != null) { if (effectiveArtwork != null) baseColors.dynamicBackdropGlass() else baseColors }
    val motionScheme = MotionScheme.expressive()
    val animatedColors = animateColorScheme(colors, motionScheme.defaultEffectsSpec())
    MaterialExpressiveTheme(colorScheme = animatedColors, typography = typography, motionScheme = motionScheme, shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )) {
        CompositionLocalProvider(LocalContentColor provides animatedColors.onSurface, content = content)
    }
}

private fun ColorScheme.dynamicBackdropGlass(): ColorScheme =
    copy(
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = .8f),
        background = Color.Transparent,
        // In dynamic mode the single root DynamicAppBackdrop is the page background. These roles
        // are what Scaffold and most full-screen roots paint, so making them translucent merely
        // layers the old page color over the colour field and looks like a foreground overlay.
        surface = Color.Transparent,
        surfaceDim = Color.Transparent,
        surfaceBright = Color.Transparent,
        surfaceContainerLowest = Color.Transparent,
        // Component containers stay frosted/readable over that one shared backdrop.
        surfaceContainerLow = surfaceContainerLow.copy(alpha = 0.44f),
        surfaceContainer = surfaceContainer.copy(alpha = 0.52f),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = 0.60f),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = 0.68f),
        surfaceVariant = surfaceVariant.copy(alpha = 0.72f),
        primaryContainer = primaryContainer.copy(alpha = 0.88f),
        secondaryContainer = secondaryContainer.copy(alpha = 0.88f),
        tertiaryContainer = tertiaryContainer.copy(alpha = 0.88f),
        outline = outline.copy(alpha = 0.74f),
        outlineVariant = outlineVariant.copy(alpha = 0.16f),
    )

@Composable
private fun animateColorScheme(
    targetColorScheme: ColorScheme,
    animationSpec: FiniteAnimationSpec<Color>,
): ColorScheme =
    ColorScheme(
        primary = animateColorAsState(targetColorScheme.primary, animationSpec, label = "primary").value,
        onPrimary = animateColorAsState(targetColorScheme.onPrimary, animationSpec, label = "onPrimary").value,
        primaryContainer = animateColorAsState(targetColorScheme.primaryContainer, animationSpec, label = "primaryContainer").value,
        onPrimaryContainer = animateColorAsState(targetColorScheme.onPrimaryContainer, animationSpec, label = "onPrimaryContainer").value,
        inversePrimary = animateColorAsState(targetColorScheme.inversePrimary, animationSpec, label = "inversePrimary").value,
        secondary = animateColorAsState(targetColorScheme.secondary, animationSpec, label = "secondary").value,
        onSecondary = animateColorAsState(targetColorScheme.onSecondary, animationSpec, label = "onSecondary").value,
        secondaryContainer = animateColorAsState(targetColorScheme.secondaryContainer, animationSpec, label = "secondaryContainer").value,
        onSecondaryContainer =
            animateColorAsState(
                targetColorScheme.onSecondaryContainer,
                animationSpec,
                label = "onSecondaryContainer",
            ).value,
        tertiary = animateColorAsState(targetColorScheme.tertiary, animationSpec, label = "tertiary").value,
        onTertiary = animateColorAsState(targetColorScheme.onTertiary, animationSpec, label = "onTertiary").value,
        tertiaryContainer = animateColorAsState(targetColorScheme.tertiaryContainer, animationSpec, label = "tertiaryContainer").value,
        onTertiaryContainer =
            animateColorAsState(
                targetColorScheme.onTertiaryContainer,
                animationSpec,
                label = "onTertiaryContainer",
            ).value,
        background = animateColorAsState(targetColorScheme.background, animationSpec, label = "background").value,
        onBackground = animateColorAsState(targetColorScheme.onBackground, animationSpec, label = "onBackground").value,
        surface = animateColorAsState(targetColorScheme.surface, animationSpec, label = "surface").value,
        onSurface = animateColorAsState(targetColorScheme.onSurface, animationSpec, label = "onSurface").value,
        surfaceVariant = animateColorAsState(targetColorScheme.surfaceVariant, animationSpec, label = "surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(targetColorScheme.onSurfaceVariant, animationSpec, label = "onSurfaceVariant").value,
        surfaceTint = animateColorAsState(targetColorScheme.surfaceTint, animationSpec, label = "surfaceTint").value,
        inverseSurface = animateColorAsState(targetColorScheme.inverseSurface, animationSpec, label = "inverseSurface").value,
        inverseOnSurface = animateColorAsState(targetColorScheme.inverseOnSurface, animationSpec, label = "inverseOnSurface").value,
        error = animateColorAsState(targetColorScheme.error, animationSpec, label = "error").value,
        onError = animateColorAsState(targetColorScheme.onError, animationSpec, label = "onError").value,
        errorContainer = animateColorAsState(targetColorScheme.errorContainer, animationSpec, label = "errorContainer").value,
        onErrorContainer = animateColorAsState(targetColorScheme.onErrorContainer, animationSpec, label = "onErrorContainer").value,
        outline = animateColorAsState(targetColorScheme.outline, animationSpec, label = "outline").value,
        outlineVariant = animateColorAsState(targetColorScheme.outlineVariant, animationSpec, label = "outlineVariant").value,
        scrim = animateColorAsState(targetColorScheme.scrim, animationSpec, label = "scrim").value,
        surfaceBright = animateColorAsState(targetColorScheme.surfaceBright, animationSpec, label = "surfaceBright").value,
        surfaceDim = animateColorAsState(targetColorScheme.surfaceDim, animationSpec, label = "surfaceDim").value,
        surfaceContainer = animateColorAsState(targetColorScheme.surfaceContainer, animationSpec, label = "surfaceContainer").value,
        surfaceContainerLow =
            animateColorAsState(
                targetColorScheme.surfaceContainerLow,
                animationSpec,
                label = "surfaceContainerLow",
            ).value,
        surfaceContainerLowest =
            animateColorAsState(
                targetColorScheme.surfaceContainerLowest,
                animationSpec,
                label = "surfaceContainerLowest",
            ).value,
        surfaceContainerHigh =
            animateColorAsState(
                targetColorScheme.surfaceContainerHigh,
                animationSpec,
                label = "surfaceContainerHigh",
            ).value,
        surfaceContainerHighest =
            animateColorAsState(
                targetColorScheme.surfaceContainerHighest,
                animationSpec,
                label = "surfaceContainerHighest",
            ).value,
    )

