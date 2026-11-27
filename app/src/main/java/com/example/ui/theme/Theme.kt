package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SoraDarkColorScheme = darkColorScheme(
    primary = SoraViolet,
    onPrimary = Color(0xFF0E0D1A),
    primaryContainer = SoraIndigoContainer,
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = SoraSoftBlue,
    onSecondary = Color(0xFF0A192F),
    secondaryContainer = SoraSoftBlueContainer,
    onSecondaryContainer = Color(0xFFD6E9FF),
    tertiary = SoraSuccess,
    onTertiary = Color(0xFF052418),
    tertiaryContainer = SoraSuccessContainer,
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = SoraDarkBg,
    onBackground = SoraDarkText,
    surface = SoraDarkSurface,
    onSurface = SoraDarkText,
    surfaceVariant = SoraDarkSurfaceElevated,
    onSurfaceVariant = SoraDarkTextMuted,
    surfaceContainerLow = SoraDarkSurface,
    surfaceContainer = SoraDarkSurfaceElevated,
    surfaceContainerHigh = SoraDarkSurfaceHighest,
    outline = SoraDarkOutline,
    outlineVariant = Color(0xFF23283B),
    error = SoraError,
    onError = Color(0xFF2B0B0E),
    errorContainer = SoraErrorContainer,
    onErrorContainer = Color(0xFFFECACA)
)

private val SoraLightColorScheme = lightColorScheme(
    primary = SoraLightPrimary,
    onPrimary = Color.White,
    primaryContainer = SoraLightPrimaryContainer,
    onPrimaryContainer = Color(0xFF23187A),
    secondary = SoraLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = SoraLightSecondaryContainer,
    onSecondaryContainer = Color(0xFF102B65),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    background = SoraLightBg,
    onBackground = SoraLightText,
    surface = SoraLightSurface,
    onSurface = SoraLightText,
    surfaceVariant = SoraLightSurfaceElevated,
    onSurfaceVariant = SoraLightTextMuted,
    surfaceContainerLow = SoraLightSurface,
    surfaceContainer = SoraLightSurfaceElevated,
    surfaceContainerHigh = SoraLightSurfaceHighest,
    outline = SoraLightOutline,
    outlineVariant = Color(0xFFE5E9F2),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val SoraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

enum class SoraThemeMode {
    SYSTEM, DARK, LIGHT
}

@Composable
fun SoraTheme(
    themeMode: SoraThemeMode = SoraThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        SoraThemeMode.DARK -> true
        SoraThemeMode.LIGHT -> false
        SoraThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialTheme(
        colorScheme = if (useDark) SoraDarkColorScheme else SoraLightColorScheme,
        typography = SoraTypography,
        shapes = SoraShapes,
        content = content
    )
}
