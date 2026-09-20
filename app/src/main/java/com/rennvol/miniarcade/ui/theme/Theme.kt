package com.rennvol.miniarcade.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DisplayFamily = FontFamily.SansSerif
private val BodyFamily = FontFamily.SansSerif
private val MonoFamily = FontFamily.Monospace

private val LightColors = lightColorScheme(
    primary = ArcadeTokens.Primary,
    onPrimary = ArcadeTokens.OnPrimary,
    primaryContainer = ArcadeTokens.PrimaryContainer,
    secondary = ArcadeTokens.Accent,
    secondaryContainer = ArcadeTokens.AccentContainer,
    background = ArcadeTokens.Bg,
    surface = ArcadeTokens.Surface,
    surfaceVariant = ArcadeTokens.SurfaceAlt,
    onBackground = ArcadeTokens.Text,
    onSurface = ArcadeTokens.Text,
    outline = ArcadeTokens.Border,
    error = ArcadeTokens.Danger
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = BodyFamily, fontSize = 13.sp, color = ArcadeTokens.TextMuted),
    labelLarge = TextStyle(fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = MonoFamily, fontSize = 11.sp, letterSpacing = 0.5.sp)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MiniArcadeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, typography = AppTypography, shapes = AppShapes, content = content)
}
