package com.majorgym.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object GymColors {
    val Bg = Color(0xFF101317)
    val Surface = Color(0xFF191F26)
    val Surface2 = Color(0xFF212A33)
    val Border = Color(0x1FFFFFFF)
    val Accent = Color(0xFFFF5A36)
    val Gold = Color(0xFFD8B44A)
    val Success = Color(0xFF43D08A)
    val Warning = Color(0xFFFFB020)
    val Danger = Color(0xFFFF5468)
    val Text = Color(0xFFF3F4F6)
    val TextMuted = Color(0xFF8B93A1)
    val TextFaint = Color(0xFF5B6472)
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
