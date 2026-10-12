package com.pardocs.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ParDocsColors = darkColorScheme(
    primary = Violet,
    onPrimary = OnViolet,
    primaryContainer = VioletDeep,
    onPrimaryContainer = VioletBright,
    secondary = Teal,
    onSecondary = Color(0xFF002018),
    secondaryContainer = TealDeep,
    onSecondaryContainer = Color(0xFF7FF5D8),
    tertiary = Salmon,
    onTertiary = Color(0xFF29060C),
    background = BlackPure,
    onBackground = TextPrimary,
    surface = BlackPure,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceRaised,
    surfaceContainerHigh = Color(0xFF131319),
    surfaceContainerHighest = Color(0xFF1A1A22),
    surfaceContainerLow = Color(0xFF0C0C11),
    surfaceContainerLowest = Color(0xFF000000),
    outline = Hairline,
    outlineVariant = Color(0xFF20202A),
    error = Salmon,
    onError = Color(0xFF2B0509),
    errorContainer = Color(0xFF3A1016),
    onErrorContainer = Color(0xFFFFDAE0),
)

@Composable
fun ParDocsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ParDocsColors,
        typography = Typography,
        content = content,
    )
}