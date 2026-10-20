package com.agent.jarvis.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Jarvis's visual identity, carried over from arbloodhound.xyz
 * (assets/style.css, dark theme — the site's default). The ground is the
 * site's black oil-pastel field (#111014) with warm sketchbook-paper text
 * (#F3EFE7); the accent is the site's sky (#3FB6F2), with its pink
 * (#FF63A8) as the secondary and marigold (#F5C632, the site's warning
 * colour) as the tertiary. Borders are the site's warm hairlines —
 * paper white at 10%/20% over the black ground.
 *
 * Type substitutions (the site's faces can't be bundled here): the
 * hand-lettered display face ("ArBloodhound", fallback Amatic SC) is
 * stood in for by bold titles with a touch of letter-spacing; the site's
 * JetBrains Mono labels/eyebrows map to the platform monospace; Space
 * Grotesk body text maps to the default sans. Corner radii are the
 * site's hand-drawn asymmetric set (cards: 6/12/8/10).
 */
private val Sky = Color(0xFF3FB6F2)

private val DarkColors = darkColorScheme(
    primary = Sky,
    onPrimary = Color(0xFF0B0A0D),
    primaryContainer = Color(0xFF0E2A3D),
    onPrimaryContainer = Color(0xFF79CFF6),
    secondary = Color(0xFFFF63A8),
    onSecondary = Color(0xFF141218),
    secondaryContainer = Color(0xFF3A1626),
    onSecondaryContainer = Color(0xFFFF86BB),
    tertiary = Color(0xFFF5C632),
    onTertiary = Color(0xFF0B0A0D),
    tertiaryContainer = Color(0xFF38300E),
    onTertiaryContainer = Color(0xFFF5C632),
    background = Color(0xFF111014),
    onBackground = Color(0xFFF3EFE7),
    surface = Color(0xFF19171D),
    onSurface = Color(0xFFF3EFE7),
    surfaceVariant = Color(0xFF0B0A0D),
    onSurfaceVariant = Color(0xFFB0AAA0),
    error = Color(0xFFFF8590),
    onError = Color(0xFF2B0A06),
    errorContainer = Color(0xFF471820),
    onErrorContainer = Color(0xFFFFB3BA),
    outline = Color(0xFF3F3D3F),
    outlineVariant = Color(0xFF28272A),
)

/**
 * Strong hierarchy in the site's three voices: display titles (the
 * hand-lettered stand-in) bold with slight tracking, body in the default
 * sans, and labels in tracked monospace like the site's eyebrows
 * (JetBrains Mono, uppercase, .2em tracking, accent colour).
 */
private val JarvisTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 1.6.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.8.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 2.sp),
)

/** The site's hand-drawn asymmetric corner radii (cards are 6/12/8/10). */
private val JarvisShapes = Shapes(
    extraSmall = RoundedCornerShape(topStart = 3.dp, topEnd = 6.dp, bottomEnd = 4.dp, bottomStart = 5.dp),
    small = RoundedCornerShape(topStart = 5.dp, topEnd = 9.dp, bottomEnd = 6.dp, bottomStart = 8.dp),
    medium = RoundedCornerShape(topStart = 6.dp, topEnd = 12.dp, bottomEnd = 8.dp, bottomStart = 10.dp),
    large = RoundedCornerShape(topStart = 8.dp, topEnd = 16.dp, bottomEnd = 10.dp, bottomStart = 14.dp),
    extraLarge = RoundedCornerShape(24.dp),
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
