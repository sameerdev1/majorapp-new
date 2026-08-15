package com.majorgym.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * Shared motion tokens for the whole app so every animation uses the same
 * handful of durations/easings instead of ad-hoc numbers scattered around
 * the codebase. Keep additions here small and reusable — this is a design
 * system, not a dumping ground for one-off specs.
 *
 * These are *visual only* — nothing in this file may gate business logic,
 * hardware calls, or navigation. See individual call sites (screen
 * transitions, fingerprint capture, kiosk service) for the hard rule that
 * animation never delays functional work.
 */
object GymMotion {
    /** Micro-interactions: button press feedback, badge/border color snaps. */
    const val Fast = 120

    /** The default for most UI transitions: screen changes, list item
     *  placement, dialog enter/exit, status color changes. */
    const val Standard = 220

    /** Reserved for slightly larger, still-restrained moments: success
     *  screen stagger, kiosk result entrance. Do not use for anything that
     *  blocks the user from acting. */
    const val Slow = 320

    /** Slow, ambient motion only — the fingerprint "waiting" breathing ring.
     *  Never used for anything the user is blocked on. */
    const val Ambient = 1400

    /** Standard "premium" easing: quick out, gentle settle. Good default for
     *  fades, slides, and color transitions alike. */
    val StandardEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    fun <T> standardTween(durationMillis: Int = Standard): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = StandardEasing)

    fun <T> fastTween(durationMillis: Int = Fast): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = StandardEasing)
}

/**
 * Section 8: subtle press feedback for primary CTAs (Add Member, Confirm
 * Renewal, Sync Now, etc). Scales down a hair on press and back on release —
 * "I physically pressed something," not a bouncy animation. Purely visual;
 * does not touch click handling or button state.
 */
@Composable
fun Modifier.gymPressScale(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = GymMotion.fastTween(),
        label = "pressScale"
    )
    return this.scale(scale)
}

/** Lightweight reduced-motion preference, read at the app root and threaded
 *  down via CompositionLocal. Only gates decorative (Priority 3) motion —
 *  functional feedback (press states, color changes signalling status) stays
 *  on even when this is true, per spec section 24. Defaults to false so
 *  behavior is unchanged unless a screen explicitly opts in. */
val LocalReducedMotion = compositionLocalOf { false }
