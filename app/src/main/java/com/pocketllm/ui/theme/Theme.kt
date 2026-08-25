package com.pocketllm.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ABFFF),
    onPrimary = Color(0xFF0A2A5E),
    primaryContainer = Color(0xFF254377),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF7FD8B4),
    tertiary = Color(0xFFE4B9FF),
    background = Color(0xFF10131A),
    surface = Color(0xFF161A23),
    surfaceContainer = Color(0xFF1B202B),
    surfaceContainerHigh = Color(0xFF232936),
    surfaceVariant = Color(0xFF232936),
    onSurface = Color(0xFFE2E6EE),
    onSurfaceVariant = Color(0xFFB9C0CE),
    outline = Color(0xFF5A6475),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B64B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF2A6B55),
    tertiary = Color(0xFF7A4BA8),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PocketLLMTheme(
    themeMode: String = "system",
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content,
    )
}
