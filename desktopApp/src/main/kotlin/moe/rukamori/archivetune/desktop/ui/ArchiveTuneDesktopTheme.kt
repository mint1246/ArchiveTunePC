/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.desktop.state.DesktopThemePreference
import java.awt.Color as AwtColor

private val ArchiveTuneGreen = Color(0xFF9FC8A6)
private val ArchiveTuneGreenContainer = Color(0xFF244C31)
private val ArchiveTuneInk = Color(0xFF101412)
private val ArchiveTuneSurface = Color(0xFF171C19)

private val ArchiveTuneDarkColors = darkColorScheme(
    primary = ArchiveTuneGreen,
    onPrimary = Color(0xFF0A3819),
    primaryContainer = ArchiveTuneGreenContainer,
    onPrimaryContainer = Color(0xFFC0F0C7),
    secondary = Color(0xFFB6CCB8),
    onSecondary = Color(0xFF223528),
    secondaryContainer = Color(0xFF384B3D),
    onSecondaryContainer = Color(0xFFD2E8D3),
    tertiary = Color(0xFFAFCBDD),
    onTertiary = Color(0xFF153442),
    background = ArchiveTuneInk,
    onBackground = Color(0xFFE1E5E1),
    surface = ArchiveTuneSurface,
    onSurface = Color(0xFFE1E5E1),
    surfaceDim = Color(0xFF0F1411),
    surfaceBright = Color(0xFF353A36),
    surfaceContainerLowest = Color(0xFF0B0F0D),
    surfaceContainerLow = Color(0xFF171C19),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632),
    surfaceVariant = Color(0xFF3F4941),
    onSurfaceVariant = Color(0xFFBFC9C0),
    outline = Color(0xFF89938A),
    outlineVariant = Color(0xFF3F4941),
)

private val ArchiveTuneLightColors = lightColorScheme(
    primary = Color(0xFF376A43),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F1C1),
    onPrimaryContainer = Color(0xFF00210C),
    secondary = Color(0xFF506353),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD2E8D3),
    onSecondaryContainer = Color(0xFF0E1F13),
    tertiary = Color(0xFF386579),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBCE9FF),
    onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFF7FBF5),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFF7FBF5),
    onSurface = Color(0xFF191C19),
    surfaceDim = Color(0xFFD7DBD5),
    surfaceBright = Color(0xFFF7FBF5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5EF),
    surfaceContainer = Color(0xFFEBEFE9),
    surfaceContainerHigh = Color(0xFFE5E9E3),
    surfaceContainerHighest = Color(0xFFDFE3DD),
    surfaceVariant = Color(0xFFDBE5DB),
    onSurfaceVariant = Color(0xFF3F4941),
    outline = Color(0xFF6F7970),
    outlineVariant = Color(0xFFBFC9C0),
)

private fun dynamicDarkColorScheme(palette: DynamicThemePalette) = darkColorScheme(
    primary = palette.primary.dynamicTone(saturation = 0.76f, brightness = 0.88f),
    onPrimary = palette.primary.dynamicTone(saturation = 0.58f, brightness = 0.11f),
    primaryContainer = palette.primary.dynamicTone(saturation = 0.64f, brightness = 0.28f).copy(alpha = 0.88f),
    onPrimaryContainer = palette.primary.dynamicTone(saturation = 0.20f, brightness = 0.94f),
    secondary = palette.secondary.dynamicTone(saturation = 0.50f, brightness = 0.84f),
    onSecondary = palette.secondary.dynamicTone(saturation = 0.42f, brightness = 0.12f),
    secondaryContainer = palette.secondary.dynamicTone(saturation = 0.46f, brightness = 0.27f).copy(alpha = 0.88f),
    onSecondaryContainer = palette.secondary.dynamicTone(saturation = 0.16f, brightness = 0.93f),
    tertiary = palette.tertiary.dynamicTone(saturation = 0.54f, brightness = 0.86f),
    onTertiary = palette.tertiary.dynamicTone(saturation = 0.44f, brightness = 0.12f),
    tertiaryContainer = palette.tertiary.dynamicTone(saturation = 0.48f, brightness = 0.28f).copy(alpha = 0.88f),
    onTertiaryContainer = palette.tertiary.dynamicTone(saturation = 0.16f, brightness = 0.94f),
    background = Color.Transparent,
    onBackground = Color(0xFFF4F4F4),
    surface = palette.neutral.dynamicTone(saturation = 0.08f, brightness = 0.12f).copy(alpha = 0.88f),
    onSurface = Color(0xFFF1F1F1),
    surfaceDim = palette.neutral.dynamicTone(saturation = 0.08f, brightness = 0.08f).copy(alpha = 0.94f),
    surfaceBright = palette.neutral.dynamicTone(saturation = 0.06f, brightness = 0.25f).copy(alpha = 0.80f),
    surfaceContainerLowest = palette.neutral.dynamicTone(saturation = 0.06f, brightness = 0.08f).copy(alpha = 0.58f),
    surfaceContainerLow = palette.neutral.dynamicTone(saturation = 0.07f, brightness = 0.11f).copy(alpha = 0.60f),
    surfaceContainer = palette.neutral.dynamicTone(saturation = 0.07f, brightness = 0.14f).copy(alpha = 0.64f),
    surfaceContainerHigh = palette.neutral.dynamicTone(saturation = 0.07f, brightness = 0.18f).copy(alpha = 0.68f),
    surfaceContainerHighest = palette.neutral.dynamicTone(saturation = 0.06f, brightness = 0.23f).copy(alpha = 0.74f),
    surfaceVariant = palette.neutral.dynamicTone(saturation = 0.07f, brightness = 0.25f).copy(alpha = 0.72f),
    onSurfaceVariant = Color(0xFFD5D6D8),
    outline = Color(0xFFB6B7BB).copy(alpha = 0.74f),
    outlineVariant = Color(0xFFF0F1F4).copy(alpha = 0.16f),
)

internal val LocalDynamicThemeActive = staticCompositionLocalOf { false }

@Composable
internal fun dynamicGlassColor(base: Color, alpha: Float): Color =
    if (LocalDynamicThemeActive.current) base.copy(alpha = alpha) else base

@Composable
internal fun dynamicGlassBorder(alpha: Float = 0.12f): BorderStroke? =
    if (LocalDynamicThemeActive.current) BorderStroke(1.dp, Color.White.copy(alpha = alpha)) else null

private fun Color.dynamicTone(saturation: Float, brightness: Float): Color {
    val hsb = AwtColor.RGBtoHSB(
        (red * 255f).toInt().coerceIn(0, 255),
        (green * 255f).toInt().coerceIn(0, 255),
        (blue * 255f).toInt().coerceIn(0, 255),
        null,
    )
    val rgb = AwtColor.HSBtoRGB(hsb[0], saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))
    return Color(
        red = (rgb ushr 16 and 0xFF) / 255f,
        green = (rgb ushr 8 and 0xFF) / 255f,
        blue = (rgb and 0xFF) / 255f,
        alpha = 1f,
    )
}

private val Poppins = FontFamily(
    Font("fonts/poppins.ttf", weight = FontWeight.Normal),
)

private val ArchiveTuneTypography = Typography(
    displayLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 60.sp),
    displaySmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

private val ArchiveTuneShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun ArchiveTuneDesktopTheme(
    preference: DesktopThemePreference,
    dynamicPalette: DynamicThemePalette = DynamicThemePalette.Fallback,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        DesktopThemePreference.SYSTEM -> isSystemInDarkTheme()
        DesktopThemePreference.LIGHT -> false
        DesktopThemePreference.DARK -> true
        DesktopThemePreference.DYNAMIC -> true
    }
    val animatedPrimary by animateColorAsState(
        targetValue = dynamicPalette.primary,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-primary",
    )
    val animatedSecondary by animateColorAsState(
        targetValue = dynamicPalette.secondary,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-secondary",
    )
    val animatedTertiary by animateColorAsState(
        targetValue = dynamicPalette.tertiary,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-tertiary",
    )
    val animatedNeutral by animateColorAsState(
        targetValue = dynamicPalette.neutral,
        animationSpec = tween(650, easing = FastOutSlowInEasing),
        label = "dynamic-neutral",
    )
    val colors = when (preference) {
        DesktopThemePreference.DYNAMIC -> dynamicDarkColorScheme(
            DynamicThemePalette(animatedPrimary, animatedSecondary, animatedTertiary, animatedNeutral),
        )
        else -> if (dark) ArchiveTuneDarkColors else ArchiveTuneLightColors
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = ArchiveTuneShapes,
        typography = ArchiveTuneTypography,
    ) {
        CompositionLocalProvider(
            LocalDynamicThemeActive provides (preference == DesktopThemePreference.DYNAMIC),
            content = content,
        )
    }
}
