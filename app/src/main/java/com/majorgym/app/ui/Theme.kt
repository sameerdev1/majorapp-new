package com.majorgym.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium "Obsidian" color system, matched exactly to the final Stitch
// redesign's design tokens (deep charcoal glass panels + cyan accent +
// violet secondary). Only the color values changed here — every field name
// and the theme function name below are unchanged, so nothing else in
// the app needed to be touched.
object GymColors {
    val Bg = Color(0xFF0A0A0B)
    val Surface = Color(0xFF201F20)
    val Surface2 = Color(0xFF2A2A2B)
    val Border = Color(0x14FFFFFF)
    val Accent = Color(0xFF00DBE9)
    val Gold = Color(0xFFD1BCFF)
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
    onBackground = GymColors.Text,
    onSurface = GymColors.Text
)

@Composable
fun MajorGymTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
