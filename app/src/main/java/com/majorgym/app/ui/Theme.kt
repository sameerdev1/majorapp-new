package com.majorgym.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium "Elite" color system, matched to the Stitch redesign's
// Hyper-Glassmorphism design tokens (deep charcoal + cyan/violet accents)
// so the app reads as one consistent design system. Only the color values
// changed here — every field name and the theme function name below are
// unchanged, so nothing else in the app needed to be touched.
object GymColors {
    val Bg = Color(0xFF0A0A0B)
    val Surface = Color(0xFF1E2021)
    val Surface2 = Color(0xFF282A2B)
    val Border = Color(0x26FFFFFF)
    val Accent = Color(0xFF00F0FF)
    val Gold = Color(0xFF8B5CF6)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Text = Color(0xFFE2E2E3)
    val TextMuted = Color(0xFFB9CACB)
    val TextFaint = Color(0xFF849495)
}

private val DarkColors = darkColorScheme(
    background = GymColors.Bg,
    surface = GymColors.Surface,
    primary = GymColors.Accent,
    onBackground = GymColors.Text,
    onSurface = GymColors.Text
)

@Composable
fun MajorGymTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
