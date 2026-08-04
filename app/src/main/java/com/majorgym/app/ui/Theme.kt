package com.majorgym.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Premium blue color system, matched to the MajorGym client app's theme
// (see the client's ClientColors) so both apps read as one consistent
// design system. Only the color values changed here — every field name
// and the theme function name below are unchanged, so nothing else in
// the app needed to be touched.
object GymColors {
    val Bg = Color(0xFF090E18)
    val Surface = Color(0xFF111827)
    val Surface2 = Color(0xFF1E293B)
    val Border = Color(0x263B82F6)
    val Accent = Color(0xFF3B82F6)
    val Gold = Color(0xFF60A5FA)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Text = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFF94A3B8)
    val TextFaint = Color(0xFF64748B)
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
