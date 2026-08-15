package com.majorgym.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majorgym.app.data.Member
import com.majorgym.app.kiosk.FingerprintKioskService
import com.majorgym.app.kiosk.KioskBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** What the kiosk is currently showing. IDLE = dashboard visible, silently listening. */
enum class KioskPhase { IDLE, MEMBER_ACTIVE, MEMBER_EXPIRED, NOT_RECOGNIZED }

/** How often to re-check for a connected scanner / re-request the service while idle. */
private const val SERVICE_RETRY_MS = 3000L

/**
 * Bridges [FingerprintKioskService] (the actual scanner owner — see that class
 * for why scanning lives in a service, not here) to this existing overlay UI.
 * This composable does no scanning itself: it only
 *  (a) asks the service to start once a scanner is actually detected, or to
 *      stop while [paused] (enrollment needs exclusive USB access), and
 *  (b) reads [KioskBus] to know what the (unchanged) overlay below should show,
 *      whether that result arrived while this app was foreground or background.
 */
@Composable
fun rememberKioskCoordinator(
    context: Context,
    members: List<Member>,
    paused: Boolean
): State<Pair<KioskPhase, Member?>> {
    val event by KioskBus.current.collectAsState()
    val currentPaused by rememberUpdatedState(paused)
    val currentMembers by rememberUpdatedState(members)

    // Fires the moment `paused` flips, instead of waiting for the next poll
    // tick below (up to SERVICE_RETRY_MS late) — that gap was part of why
    // enrollment used to race the background scanner for the USB device.
    LaunchedEffect(paused) {
        if (paused) {
            FingerprintKioskService.requestStop(context)
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            if (currentPaused) {
                FingerprintKioskService.requestStop(context)
            } else if (FingerprintKioskService.isScannerConnected(context)) {
                FingerprintKioskService.requestStart(context)
            }
            delay(SERVICE_RETRY_MS)
        }
    }

    return remember {
        derivedStateOf {
            val e = event
            when {
                e == null -> KioskPhase.IDLE to null
                !e.recognized -> KioskPhase.NOT_RECOGNIZED to null
                e.expired -> KioskPhase.MEMBER_EXPIRED to currentMembers.find { it.id == e.matchedMemberId }
                else -> KioskPhase.MEMBER_ACTIVE to currentMembers.find { it.id == e.matchedMemberId }
            }
        }
    }
}

/**
 * Full-screen result shown over whatever screen is currently visible when a
 * scan resolves. Renders nothing while [phase] is IDLE.
 */
@Composable
fun KioskResultOverlay(phase: KioskPhase, member: Member?) {
    // Section 9: this must appear almost immediately (staff read it at a
    // glance) so entrance stays short — fade + a small scale-up, never a
    // slow reveal. Purely visual: KioskBus/the service already resolved the
    // scan result before this composable is even asked to show it.
    AnimatedVisibility(
        visible = phase != KioskPhase.IDLE,
        enter = fadeIn(tween(GymMotion.Fast, easing = GymMotion.StandardEasing)) +
            scaleIn(initialScale = 0.92f, animationSpec = tween(GymMotion.Fast, easing = GymMotion.StandardEasing)),
        exit = fadeOut(tween(GymMotion.Fast, easing = GymMotion.StandardEasing)) +
            scaleOut(targetScale = 0.96f, animationSpec = tween(GymMotion.Fast, easing = GymMotion.StandardEasing))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(GymColors.SurfaceCard)
                    .border(1.dp, GymColors.Border, RoundedCornerShape(24.dp))
                    .padding(32.dp)
            ) {
                when (phase) {
                    KioskPhase.MEMBER_ACTIVE, KioskPhase.MEMBER_EXPIRED -> {
                        val photoFile = member?.photoPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(CircleShape)
                                .background(GymColors.Surface2)
                                .border(3.dp, if (phase == KioskPhase.MEMBER_ACTIVE) GymColors.Success else GymColors.Danger, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (photoFile != null) {
                                val bmp = remember(photoFile) { android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath) }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(), contentDescription = null,
                                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else PersonPlaceholder()
                            } else PersonPlaceholder()
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            member?.name ?: "", color = Color.White, fontSize = 26.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        if (phase == KioskPhase.MEMBER_ACTIVE) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(GymColors.Success.copy(alpha = 0.15f))
                                    .border(1.dp, GymColors.Success.copy(alpha = 0.4f), RoundedCornerShape(50))
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.CheckCircle, null, tint = GymColors.Success, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("MEMBER ACTIVE", color = GymColors.Success, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(GymColors.Danger.copy(alpha = 0.15f))
                                    .border(1.dp, GymColors.Danger.copy(alpha = 0.4f), RoundedCornerShape(50))
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Filled.Close, null, tint = GymColors.Danger, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("MEMBERSHIP EXPIRED", color = GymColors.Danger, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp)
                            }
                        }
                    }
                    KioskPhase.NOT_RECOGNIZED -> {
                        Icon(Icons.Filled.Close, null, tint = GymColors.Danger, modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Member Not Recognized", color = GymColors.Danger, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    KioskPhase.IDLE -> {}
                }
            }
        }
    }
}

@Composable
private fun PersonPlaceholder() {
    Icon(Icons.Filled.Person, null, tint = GymColors.Accent, modifier = Modifier.size(64.dp))
}

/**
 * Priority 3 (optional): an extremely subtle accent dot near the bottom of
 * the screen indicating the kiosk is listening while idle. This is purely
 * decorative — it does not read scanner/service state itself, it only
 * reflects the KioskPhase already computed elsewhere (IDLE = nothing being
 * shown right now), so it can never desync from or delay the actual kiosk
 * logic. Respects reduced motion: the loop is skipped and the dot renders
 * as a static, still-faint mark instead.
 */
@Composable
fun KioskIdleIndicator(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(GymMotion.Standard, easing = GymMotion.StandardEasing)),
        exit = fadeOut(tween(GymMotion.Fast, easing = GymMotion.StandardEasing)),
        modifier = modifier
    ) {
        val reducedMotion = LocalReducedMotion.current
        val alpha = if (reducedMotion) {
            0.35f
        } else {
            val pulse = rememberInfiniteTransition(label = "kioskIdlePulse")
            val animatedAlpha by pulse.animateFloat(
                initialValue = 0.18f,
                targetValue = 0.42f,
                animationSpec = infiniteRepeatable(
                    animation = tween(GymMotion.Ambient, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "kioskIdlePulseAlpha"
            )
            animatedAlpha
        }
        Box(
            modifier = Modifier
                .padding(16.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(GymColors.Accent.copy(alpha = alpha))
        )
    }
}

