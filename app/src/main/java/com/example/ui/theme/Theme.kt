package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Cyan400,
    onPrimary = Slate950,
    primaryContainer = Cyan600,
    onPrimaryContainer = Color.White,
    secondary = Amber400,
    onSecondary = Slate950,
    secondaryContainer = Slate700,
    onSecondaryContainer = Amber400,
    tertiary = Emerald400,
    onTertiary = Slate950,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate200,
    outline = Slate600,
    error = Rose500,
    onError = Color.White
)

private val LightColorScheme = darkColorScheme(
    // Keep high-contrast modern dark-slate for POS display ergonomics
    primary = Cyan500,
    onPrimary = Color.White,
    primaryContainer = Slate800,
    onPrimaryContainer = Cyan400,
    secondary = Amber500,
    onSecondary = Slate950,
    tertiary = Emerald500,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate200,
    outline = Slate600,
    error = Rose500
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
