package com.pocketllm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ABFFF),
    onPrimary = Color(0xFF0A2A5E),
    primaryContainer = Color(0xFF254377),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF7FD8B4),
    background = Color(0xFF10131A),
    surface = Color(0xFF161A23),
    surfaceVariant = Color(0xFF232936),
    onSurface = Color(0xFFE2E6EE),
    onSurfaceVariant = Color(0xFFB9C0CE),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme()

@Composable
fun PocketLLMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
