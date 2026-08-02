package com.majorgym.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

/**
 * Premium launch splash — shown before the Dashboard. See the three modes
 * below; which one runs is decided once, when the app opens.
 *
 * FULL   — the full animated sequence (~2.8s): ambient background glow,
 *          logo fade/scale/bounce with a golden halo, a fitness-energy pulse,
 *          a metallic light sweep, a handful of particles drifting inward,
 *          brand text, and a brief "Ready" beat. Shown on first install,
 *          after an app update, and on the first launch each day.
 * QUICK  — just the logo fading in over 500ms. Shown on every other launch
 *          the same day, so returning to the app repeatedly doesn't make the
 *          owner sit through the full sequence every time.
 * STATIC — a still, non-animated version held for 1 second, used when the
 *          system's "reduce motion" developer setting is on
 *          (ANIMATOR_DURATION_SCALE == 0), since there's no single universal
 *          "reduce motion" flag available across all supported Android
 *          versions (this app supports 8+) — this is the standard proxy most
 *          apps check.
 *
 * Nothing here touches the database, navigation, or any other screen —
 * MainActivity just shows this first and calls onFinished() to hand off to
 * the existing app content, exactly as before.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Decided once per launch — the splash is only ever composed a single time.
    val mode = remember { decideSplashMode(context) }
    LaunchedEffect(Unit) { markSplashShown(context) }

    when (mode) {
        SplashMode.FULL -> FullSplash(onHapticTick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }, onFinished = onFinished)
        SplashMode.QUICK -> QuickSplash(onFinished)
        SplashMode.STATIC -> StaticSplash(onFinished)
    }
}

private enum class SplashMode { FULL, QUICK, STATIC }

private const val SPLASH_PREFS = "majorgym_splash"
private const val KEY_LAST_DATE = "last_shown_date"
private const val KEY_LAST_VERSION = "last_shown_version"

private fun todayString(): String = LocalDate.now().toString()

private fun currentVersionCode(context: Context): Long = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    PackageInfoCompat.getLongVersionCode(info)
} catch (e: Exception) {
    0L
}

private fun reducedMotionEnabled(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (e: Exception) {
    false
}

private fun decideSplashMode(context: Context): SplashMode {
    if (reducedMotionEnabled(context)) return SplashMode.STATIC
    val prefs = context.getSharedPreferences(SPLASH_PREFS, Context.MODE_PRIVATE)
    val lastDate = prefs.getString(KEY_LAST_DATE, null)
    val lastVersion = prefs.getLong(KEY_LAST_VERSION, -1L)
    val isFirstToday = lastDate != todayString()
    val isAfterUpdate = lastVersion != currentVersionCode(context)
    return if (isFirstToday || isAfterUpdate) SplashMode.FULL else SplashMode.QUICK
}

private fun markSplashShown(context: Context) {
    context.getSharedPreferences(SPLASH_PREFS, Context.MODE_PRIVATE).edit()
        .putString(KEY_LAST_DATE, todayString())
        .putLong(KEY_LAST_VERSION, currentVersionCode(context))
        .apply()
}

// ---------- Quick / static variants ----------

@Composable
private fun QuickSplash(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, animationSpec = tween(500))
        onFinished()
    }
    Box(Modifier.fillMaxSize().background(GymColors.Bg), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.FitnessCenter, null, tint = GymColors.Accent,
            modifier = Modifier.size(64.dp).graphicsLayer { this.alpha = alpha.value }
        )
    }
}

@Composable
private fun StaticSplash(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1000)
        onFinished()
    }
    Box(Modifier.fillMaxSize().background(GymColors.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.FitnessCenter, null, tint = GymColors.Accent, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("MAJOR GYM", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text("Train Smarter. Manage Better.", color = GymColors.TextMuted, fontSize = 12.sp)
        }
    }
}

// ---------- Full premium sequence ----------

private const val TOTAL_MS = 2800

// Phase windows, in ms from splash start. Kept as one linear timeline driven
// by a single Animatable so the whole thing is guaranteed to land inside the
// 2.5-3s target rather than drifting from several independent animations.
private const val LOGO_START = 0
private const val LOGO_END = 600
private const val PULSE_START = 150
private const val PULSE_END = 800
private const val SWEEP_START = 650
private const val SWEEP_END = 1200
private const val PARTICLES_START = 300
private const val PARTICLES_END = 2200
private const val TEXT_START = 1400
private const val TEXT_END = 1900
private const val READY_START = 2350
private const val READY_END = 2650
private const val FADE_OUT_START = 2650

private fun phase(t: Int, start: Int, end: Int): Float =
    ((t - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)

@Composable
private fun FullSplash(onHapticTick: () -> Unit, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(TOTAL_MS, easing = LinearEasing))
        onFinished()
    }
    // Independent of frame timing — fires exactly once when the logo has landed.
    LaunchedEffect(Unit) {
        delay(LOGO_END.toLong())
        onHapticTick()
    }

    val t = (progress.value * TOTAL_MS).toInt()

    // Logo: fade in, scale 70% -> ~106% -> settle at 100% (a small overshoot
    // reads as a soft bounce without needing a spring animation to hit an
    // exact duration).
    val logoAppear = phase(t, LOGO_START, LOGO_END)
    val logoAlpha = logoAppear
    val logoScale = when {
        logoAppear < 0.7f -> 0.7f + (0.36f) * (logoAppear / 0.7f)   // 0.70 -> 1.06
        else -> 1.06f - 0.06f * ((logoAppear - 0.7f) / 0.3f)         // 1.06 -> 1.00
    }

    val pulseProgress = phase(t, PULSE_START, PULSE_END)
    val sweepProgress = phase(t, SWEEP_START, SWEEP_END)
    val textProgress = phase(t, TEXT_START, TEXT_END)
    val readyProgress = phase(t, READY_START, READY_END)
    val fadeOutAlpha = 1f - phase(t, FADE_OUT_START, TOTAL_MS)

    // Fixed particle angles/delays so positions are deterministic across
    // recompositions rather than re-randomized every frame.
    val particles = remember {
        List(8) { i -> ParticleSpec(angleDeg = i * 45f, delayMs = i * 90) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeOutAlpha }
            .background(GymColors.Bg),
        contentAlignment = Alignment.Center
    ) {
        // Ambient glow + energy pulse + particles, all drawn in one Canvas
        // for efficiency (avoids one composable per particle).
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)

            // Soft ambient glow, drifting slightly across the sequence.
            val ambientDrift = 24f * sin(progress.value * 6.28f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GymColors.Accent.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(center.x + ambientDrift, center.y),
                    radius = size.minDimension * 0.6f
                ),
                radius = size.minDimension * 0.6f,
                center = Offset(center.x + ambientDrift, center.y)
            )

            // Fitness-energy pulse: one ring, expands once and fades.
            if (pulseProgress in 0f..1f && pulseProgress > 0f) {
                val pulseRadius = 30.dp.toPx() + pulseProgress * 140.dp.toPx()
                val pulseAlpha = (1f - pulseProgress) * 0.35f
                drawCircle(
                    color = Color(0xFFFF5A36).copy(alpha = pulseAlpha),
                    radius = pulseRadius,
                    center = center,
                    style = Stroke(width = 3.dp.toPx())
                )
            }

            // Golden halo behind the logo.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(GymColors.Accent.copy(alpha = 0.28f * logoAlpha), Color.Transparent),
                    center = center,
                    radius = 90.dp.toPx()
                ),
                radius = 90.dp.toPx(),
                center = center
            )

            // Particles drifting inward, fading in then out.
            particles.forEach { p ->
                val localT = t - p.delayMs
                val duration = 1400
                if (localT in 0..duration) {
                    val lp = localT / duration.toFloat()
                    val radius = 130.dp.toPx() * (1f - lp) + 20.dp.toPx() * lp
                    val alpha = if (lp < 0.3f) lp / 0.3f else (1f - lp) / 0.7f
                    val angleRad = Math.toRadians(p.angleDeg.toDouble())
                    val pos = Offset(
                        center.x + (radius * cos(angleRad)).toFloat(),
                        center.y + (radius * sin(angleRad)).toFloat()
                    )
                    drawCircle(
                        color = GymColors.Accent.copy(alpha = (alpha * 0.8f).coerceIn(0f, 1f)),
                        radius = 2.5.dp.toPx(),
                        center = pos
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.FitnessCenter,
                    contentDescription = null,
                    tint = GymColors.Accent,
                    modifier = Modifier
                        .size(64.dp)
                        .graphicsLayer {
                            alpha = logoAlpha
                            scaleX = logoScale
                            scaleY = logoScale
                        }
                )
                // Metallic light sweep — a soft diagonal band that crosses the
                // logo once, clipped to roughly the icon's own footprint.
                if (sweepProgress in 0f..1f && logoAlpha > 0.5f) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .graphicsLayer {
                                clip = true
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp, 64.dp)
                                .offset(x = (-40).dp + (104.dp * sweepProgress))
                                .graphicsLayer { rotationZ = 20f }
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.35f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = textProgress
                    translationY = (1f - textProgress) * 20.dp.toPx()
                }
            ) {
                Text("MAJOR GYM", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text("Train Smarter. Manage Better.", color = GymColors.TextMuted, fontSize = 12.sp)
            }

            Spacer(Modifier.height(14.dp))

            if (readyProgress > 0f) {
                Box(modifier = Modifier.graphicsLayer { alpha = readyProgress }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = GymColors.Success, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ready", color = GymColors.TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private data class ParticleSpec(val angleDeg: Float, val delayMs: Int)
