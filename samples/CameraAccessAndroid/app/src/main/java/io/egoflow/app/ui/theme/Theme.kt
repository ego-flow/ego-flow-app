package io.egoflow.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemePreference { LIGHT, DARK, SYSTEM }

private val EgoFlowLightColorScheme = lightColorScheme(
    background = LightPalette.Background,
    onBackground = LightPalette.Foreground,
    surface = LightPalette.Card,
    onSurface = LightPalette.Foreground,
    surfaceVariant = LightPalette.Muted,
    onSurfaceVariant = LightPalette.MutedForeground,
    surfaceContainerHighest = LightPalette.Secondary,
    primary = LightPalette.Primary,
    onPrimary = LightPalette.PrimaryForeground,
    primaryContainer = LightPalette.Accent,
    onPrimaryContainer = LightPalette.AccentForeground,
    secondary = LightPalette.Secondary,
    onSecondary = LightPalette.SecondaryForeground,
    secondaryContainer = LightPalette.Secondary,
    onSecondaryContainer = LightPalette.SecondaryForeground,
    tertiary = BrandColors.LagoonDeepLight,
    onTertiary = Color.White,
    error = LightPalette.Destructive,
    onError = Color.White,
    errorContainer = Color(0xFFFFD8DB),
    onErrorContainer = Color(0xFFAA071E),
    outline = LightPalette.Border,
    outlineVariant = Color(0x2E284E56),
    inverseSurface = LightPalette.Foreground,
    inverseOnSurface = LightPalette.Background,
    inversePrimary = DarkPalette.Primary,
    surfaceTint = LightPalette.Primary,
    scrim = Color.Black,
)

private val EgoFlowDarkColorScheme = darkColorScheme(
    background = DarkPalette.Background,
    onBackground = DarkPalette.Foreground,
    surface = DarkPalette.Card,
    onSurface = DarkPalette.Foreground,
    surfaceVariant = DarkPalette.Muted,
    onSurfaceVariant = DarkPalette.MutedForeground,
    surfaceContainerHighest = DarkPalette.Secondary,
    primary = DarkPalette.Primary,
    onPrimary = DarkPalette.PrimaryForeground,
    primaryContainer = DarkPalette.Accent,
    onPrimaryContainer = DarkPalette.AccentForeground,
    secondary = DarkPalette.Secondary,
    onSecondary = DarkPalette.SecondaryForeground,
    secondaryContainer = DarkPalette.Secondary,
    onSecondaryContainer = DarkPalette.SecondaryForeground,
    tertiary = BrandColors.LagoonDeepDark,
    onTertiary = DarkPalette.PrimaryForeground,
    error = DarkPalette.Destructive,
    onError = Color.White,
    errorContainer = Color(0xFF5C1A10),
    onErrorContainer = Color(0xFFFFD8DB),
    outline = DarkPalette.Border,
    outlineVariant = Color(0x33CFE6EC),
    inverseSurface = DarkPalette.Foreground,
    inverseOnSurface = DarkPalette.Background,
    inversePrimary = LightPalette.Primary,
    surfaceTint = DarkPalette.Primary,
    scrim = Color.Black,
)

@Immutable
data class EgoFlowColors(
    val seaInk: Color,
    val seaInkSoft: Color,
    val lagoonDeep: Color,
    val statusGreen: Color,
    val statusYellow: Color,
    val statusRed: Color,
)

private val LightEgoFlowColors = EgoFlowColors(
    seaInk = BrandColors.SeaInkLight,
    seaInkSoft = BrandColors.SeaInkSoftLight,
    lagoonDeep = BrandColors.LagoonDeepLight,
    statusGreen = BrandColors.StatusGreen,
    statusYellow = BrandColors.StatusYellow,
    statusRed = BrandColors.StatusRed,
)

private val DarkEgoFlowColors = EgoFlowColors(
    seaInk = BrandColors.SeaInkDark,
    seaInkSoft = BrandColors.SeaInkSoftDark,
    lagoonDeep = BrandColors.LagoonDeepDark,
    statusGreen = BrandColors.StatusGreen,
    statusYellow = BrandColors.StatusYellow,
    statusRed = BrandColors.StatusRed,
)

val LocalEgoFlowColors = staticCompositionLocalOf { LightEgoFlowColors }

@Composable
fun EgoFlowTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (isDark) EgoFlowDarkColorScheme else EgoFlowLightColorScheme
    val egoFlowColors = if (isDark) DarkEgoFlowColors else LightEgoFlowColors

    CompositionLocalProvider(LocalEgoFlowColors provides egoFlowColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = EgoFlowShapes,
            content = content,
        )
    }
}
