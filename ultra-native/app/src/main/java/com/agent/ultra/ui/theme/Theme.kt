package com.agent.ultra.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Jarvis's visual identity: near-black surfaces, one restrained accent
 * (electric cyan), high-contrast text, and hairline outlines instead of
 * heavy filled boxes. Dark-only, as the app has always been (previous
 * builds forced dark via userInterfaceStyle="dark").
 */
private val Accent = Color(0xFF22D3EE)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF001A1F),
    primaryContainer = Color(0xFF0B3540),
    onPrimaryContainer = Color(0xFFA8ECFA),
    secondary = Color(0xFF8FB8C4),
    onSecondary = Color(0xFF0A2229),
    secondaryContainer = Color(0xFF16242B),
    onSecondaryContainer = Color(0xFFCDEDF5),
    background = Color(0xFF060809),
    onBackground = Color(0xFFF2F6F8),
    surface = Color(0xFF0B0E10),
    onSurface = Color(0xFFF2F6F8),
    surfaceVariant = Color(0xFF15191C),
    onSurfaceVariant = Color(0xFF9DAAB1),
    error = Color(0xFFFF8A75),
    onError = Color(0xFF2B0A06),
    errorContainer = Color(0xFF4A1710),
    onErrorContainer = Color(0xFFFFD9D2),
    outline = Color(0xFF272E33),
    outlineVariant = Color(0xFF1B2126),
)

/** Strong hierarchy: large calm titles, readable body text, tracked labels. */
private val JarvisTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.6.sp),
)

private val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AgentUltraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = JarvisTypography,
        shapes = JarvisShapes,
        content = content,
    )
}
