package com.majorgym.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared "Biometric Core OS" component library (spec section 9/19).
 * Every screen should build cards, buttons, headers, and status chips from
 * these rather than styling ad hoc, so the whole app reads as one product.
 * Nothing here owns navigation, data, or business logic — every composable
 * below is purely presentational and takes the same click/state callbacks
 * the screens already had.
 */

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** The standard premium card: dark glass surface, subtle 1px border, soft
 *  rounded corners. Use for the vast majority of content containers. */
@Composable
fun FuturisticCard(
    modifier: Modifier = Modifier,
    shape: Shape = GymShapes.lg,
    borderColor: Color = GymColors.Border,
    glow: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .let { if (glow != null) it.neonGlow(glow, shape = shape) else it }
            .clip(shape)
            .background(GymColors.SurfaceCard)
            .border(1.dp, borderColor, shape)
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(contentPadding),
        content = content
    )
}

/** A slightly brighter "glass" variant for hero/header surfaces — e.g. the
 *  Dashboard's top metric panel or a screen's hero banner. */
@Composable
fun GlassHeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = GymShapes.xl,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(GymColors.CardGlassGradient)
            .border(1.dp, GymColors.BorderGlowCyan, shape)
            .padding(20.dp)
    ) { content() }
}

// ---------------------------------------------------------------------------
// Buttons — same semantics as Material Button (enabled/disabled, onClick),
// styled with the Stitch gradient/glass language. These wrap a Row rather
// than androidx.compose.material3.Button only because Button doesn't support
// a gradient background directly; press-scale is preserved via the existing
// gymPressScale modifier from Motion.kt.
// ---------------------------------------------------------------------------

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .gymPressScale(interaction)
            .clip(GymShapes.md)
            .background(if (enabled) GymColors.PrimaryGradient else Brush.linearGradient(listOf(GymColors.Surface3, GymColors.Surface3)))
            .let { if (enabled) it.neonGlow(GymColors.AccentBright, alpha = 0.28f, shape = GymShapes.md) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color(0xFF06121A), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (enabled) Color(0xFF06121A) else GymColors.TextFaint,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            fontFamily = GymFonts.Display
        )
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .gymPressScale(interaction)
            .clip(GymShapes.md)
            .background(GymColors.Violet.copy(alpha = 0.12f))
            .border(1.dp, GymColors.Violet.copy(alpha = 0.5f), GymShapes.md)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = GymColors.Violet, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = GymColors.Violet, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = GymFonts.Display)
    }
}

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .gymPressScale(interaction)
            .clip(GymShapes.md)
            .background(GymColors.Danger.copy(alpha = 0.12f))
            .border(1.dp, GymColors.Danger.copy(alpha = 0.45f), GymShapes.md)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() }
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = GymColors.Danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = GymColors.Danger, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = GymFonts.Display)
    }
}

// ---------------------------------------------------------------------------
// Headers / labels
// ---------------------------------------------------------------------------

/** The approved global Major Gym header style (taken from the Member
 *  Attendance Details reference): Exo 2 ExtraBold, all caps, slightly open
 *  letter spacing, white-to-electric-blue gradient with a soft blue glow.
 *  Every screen title in the app goes through this so they all match. Purely
 *  typographic - it takes the same text each screen already showed. */
@Composable
fun GymHeaderText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 22.sp,
    textAlign: TextAlign? = null
) {
    Text(
        text.uppercase(),
        style = TextStyle(
            brush = GymColors.HeaderGradient,
            fontFamily = GymFonts.Header,
            fontWeight = FontWeight.ExtraBold,
            fontSize = fontSize,
            letterSpacing = 1.sp,
            shadow = Shadow(color = GymColors.HeaderGlow, offset = Offset.Zero, blurRadius = 14f)
        ),
        textAlign = textAlign,
        modifier = modifier
    )
}

/** Big uppercase screen title used at the top of every full screen - now a
 *  thin wrapper over [GymHeaderText] so all titles share one header style. */
@Composable
fun GymScreenTitle(text: String, modifier: Modifier = Modifier) {
    GymHeaderText(text, modifier = modifier, fontSize = 22.sp)
}

/** Small uppercase section label ("NEEDS ATTENTION", "MEMBERSHIP OVERVIEW"). */
@Composable
fun GymSectionLabel(text: String, modifier: Modifier = Modifier, color: Color = GymColors.TextFaint) {
    Text(
        text.uppercase(),
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        fontFamily = GymFonts.Display,
        modifier = modifier
    )
}

// ---------------------------------------------------------------------------
// Empty / loading states
// ---------------------------------------------------------------------------

@Composable
fun GymEmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Inbox) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(GymColors.Surface2)
                .padding(18.dp)
        ) {
            Icon(icon, contentDescription = null, tint = GymColors.TextFaint, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(text, color = GymColors.TextFaint, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
fun GymLoadingState(text: String = "Loading\u2026", modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp)
    ) {
        CircularProgressIndicator(color = GymColors.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = GymColors.TextFaint, fontSize = 13.sp)
    }
}
