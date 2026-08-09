package com.majorgym.app.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
    AnimatedVisibility(visible = phase != KioskPhase.IDLE, enter = fadeIn(), exit = fadeOut()) {
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

