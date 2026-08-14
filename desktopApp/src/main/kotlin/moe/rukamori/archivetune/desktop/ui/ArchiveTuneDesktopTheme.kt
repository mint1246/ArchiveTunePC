/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.rukamori.archivetune.desktop.state.DesktopThemePreference

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
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        DesktopThemePreference.SYSTEM -> isSystemInDarkTheme()
        DesktopThemePreference.LIGHT -> false
        DesktopThemePreference.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) ArchiveTuneDarkColors else ArchiveTuneLightColors,
        shapes = ArchiveTuneShapes,
        typography = ArchiveTuneTypography,
        content = content,
    )
}
