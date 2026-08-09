package com.majorgym.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Obsidian Kinetic design system color tokens
// Deep charcoal glass panels + neon cyber blue accent + deep violet secondary + glass borders
object GymColors {
    val Bg = Color(0xFF0A0A0B)
    val Surface = Color(0xFF141416)
    val SurfaceCard = Color(0xFF1C1B1C)
    val Surface2 = Color(0xFF201F20)
    val Surface3 = Color(0xFF2A2A2B)
    val Border = Color(0x24FFFFFF)
    val BorderSubtle = Color(0x14FFFFFF)
    val Accent = Color(0xFF00DBE9)
    val AccentBright = Color(0xFF00F0FF)
    val Gold = Color(0xFFD1BCFF)
    val Violet = Color(0xFF7000FF)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Text = Color(0xFFE5E2E3)
    val TextMuted = Color(0xFFB9CACB)
    val TextFaint = Color(0xFF849495)
}

private val DarkColors = darkColorScheme(
    background = GymColors.Bg,
    surface = GymColors.Surface,
    primary = GymColors.Accent,
    secondary = GymColors.Gold,
    onBackground = GymColors.Text,
    onSurface = GymColors.Text
)

@Composable
fun MajorGymTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}

