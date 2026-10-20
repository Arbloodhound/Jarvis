package com.agent.ultra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4ADE80)

private val DarkColors = darkColorScheme(
    primary = Accent,
    background = Color(0xFF000000),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE8E8E8),
    onSurface = Color(0xFFE8E8E8),
)

@Composable
fun AgentUltraTheme(content: @Composable () -> Unit) {
    // Dark-only, matching the app's established identity (the previous builds
    // forced dark via userInterfaceStyle="dark").
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
