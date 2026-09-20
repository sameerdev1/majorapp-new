package com.majorgym.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.majorgym.app.R

// ---------------------------------------------------------------------------
// Major Gym design system — "Biometric Core OS"
//
// Visual language extracted from the Stitch futuristic-redesign reference
// (dashboard_command_center / add_member / attendance_logs /
// biometric_kiosk_terminal + DESIGN.md tokens): deep-void glass surfaces,
// electric cyan primary, violet secondary, restrained status colors.
//
// IMPORTANT: every existing GymColors field name below is preserved so the
// whole app (every screen already references these symbols) re-themes
// automatically — only the VALUES were tuned to the Stitch palette. New
// tokens (gradients, glow colors) were added, nothing removed.
// ---------------------------------------------------------------------------
object GymColors {
    // Base canvas / glass surfaces
    val Bg = Color(0xFF080B12)          // Stitch "Void Black" base canvas
    val Surface = Color(0xFF0D111A)     // elevated surface / glass background
    val SurfaceCard = Color(0xFF10141F) // card containers
    val Surface2 = Color(0xFF161B26)    // hover / secondary elevated container
    val Surface3 = Color(0xFF1F2A3D)    // highest elevation / outline-adjacent

    // Borders
    val Border = Color(0x1FFFFFFF)        // ~12% white — standard 1px card perimeter
    val BorderSubtle = Color(0x0FFFFFFF)  // ~6% white — very quiet dividers
    val BorderGlowCyan = Color(0x4D00F0FF)   // glowing accent edge (cyan)
    val BorderGlowViolet = Color(0x408B5CF6) // glowing accent edge (violet)

    // Primary / secondary accents
    val Accent = Color(0xFF00E0FF)        // interactive cyan (icons, links, active tint)
    val AccentBright = Color(0xFF00F0FF)  // primary button / glow cyan
    val AccentShift = Color(0xFF0099FF)   // gradient endpoint for primary buttons
    val Gold = Color(0xFFD1BCFF)          // light lavender — secondary highlight (money, chips)
    val Violet = Color(0xFF8B5CF6)        // secondary violet accent
    val VioletDeep = Color(0xFF7928CA)    // gradient endpoint for violet actions

    // Status / telemetry tiers
    val Success = Color(0xFF10B981)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)

    // Typography
    val Text = Color(0xFFF5F7FA)
    val TextMuted = Color(0xFF94A3B8)
    val TextFaint = Color(0xFF64748B)

    // Gradients (used by the new premium components in GymComponents.kt)
    val PrimaryGradient = Brush.linearGradient(listOf(AccentBright, AccentShift))
    val VioletGradient = Brush.linearGradient(listOf(Violet, VioletDeep))
    val SuccessGradient = Brush.linearGradient(listOf(Color(0xFF34F5C5), Success))
    val CardGlassGradient = Brush.verticalGradient(
        listOf(Color(0xFF141926), Color(0xFF0D111A))
    )
    // Global screen-header treatment (approved Member Attendance Details
    // reference): white at the top easing into electric blue, with a soft
    // cyan glow applied by GymHeaderText.
    val HeaderGradient = Brush.verticalGradient(
        listOf(Color(0xFFFFFFFF), Color(0xFFA9DBFF), Color(0xFF3F8CFF))
    )
    val HeaderGlow = Color(0xAA1E90FF)
    val ScreenBackdrop = Brush.radialGradient(
        colors = listOf(Color(0x298B5CF6), Color(0x0000F0FF), Color(0x00080B12)),
        radius = 900f
    )
}

/**
 * Space Grotesk / Plus Jakarta Sans are the Stitch typography direction, but
 * this project does not bundle any font assets — adding real .ttf files
 * wasn't part of the source Android project, and no network fetch is
 * available at build time, so shipping the exact typefaces isn't possible
 * without Sameer supplying the font files. Until then, this approximates the
 * same hierarchy (tight, technical display face for headlines/metrics;
 * plain geometric sans for body copy) using system font families so nothing
 * new needs to be bundled or downloaded:
 *  - GymFonts.Display -> sans-serif-black (headlines, screen titles, big
 *    metric counters, uppercase labels) — closest system stand-in for the
 *    Space Grotesk weight/character used throughout Stitch.
 *  - GymFonts.Body -> the default sans-serif (form fields, body copy).
 * Drop real Space Grotesk / Plus Jakarta Sans .ttf files into
 * res/font/ and swap these two FontFamily values to use them exactly.
 */
object GymFonts {
    val Display = FontFamily.SansSerif
    val Body = FontFamily.SansSerif

    /** Exo 2 (SIL OFL 1.1, bundled in res/font — license in docs/Exo2-OFL.txt).
     *  Used for screen headers app-wide and the Member Attendance Details
     *  screen's headings. Display/Body above are intentionally unchanged so
     *  no other text in the app changes typeface. */
    val Header = FontFamily(
        Font(R.font.exo2_semibold, FontWeight.SemiBold),
        Font(R.font.exo2_bold, FontWeight.Bold),
        Font(R.font.exo2_extrabold, FontWeight.ExtraBold)
    )
}

private val DarkColors = darkColorScheme(
    background = GymColors.Bg,
    surface = GymColors.Surface,
    primary = GymColors.Accent,
    secondary = GymColors.Gold,
    onBackground = GymColors.Text,
    onSurface = GymColors.Text
)

// Shared corner-radius scale (Stitch: 12-16px baseline, pills for chips/badges)
object GymShapes {
    val sm: Shape = RoundedCornerShape(8.dp)
    val md: Shape = RoundedCornerShape(12.dp)
    val lg: Shape = RoundedCornerShape(16.dp)
    val xl: Shape = RoundedCornerShape(24.dp)
    val pill: Shape = RoundedCornerShape(50)
}

/**
 * A restrained, single controlled glow — used sparingly (primary CTAs, the
 * active nav item, an active scanner viewport) rather than on every surface,
 * per the "premium, not gaming interface" direction.
 */
fun Modifier.neonGlow(color: Color, alpha: Float = 0.35f, radius: androidx.compose.ui.unit.Dp = 18.dp, shape: Shape = GymShapes.lg): Modifier =
    this.shadow(radius, shape, ambientColor = color.copy(alpha = alpha), spotColor = color.copy(alpha = alpha))

@Composable
fun MajorGymTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
