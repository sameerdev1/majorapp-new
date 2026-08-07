package com.majorgym.app.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.majorgym.app.data.MemberStatus
import com.majorgym.app.data.FingerprintScanner
import com.majorgym.app.data.statusOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** What the kiosk is currently showing. IDLE = dashboard visible, silently listening. */
enum class KioskPhase { IDLE, MEMBER_ACTIVE, MEMBER_EXPIRED, NOT_RECOGNIZED }

private const val MATCHED_DISPLAY_MS = 3000L
private const val NOT_RECOGNIZED_DISPLAY_MS = 1500L
/** How long a single "wait for finger" call blocks before we loop and try again.
 *  Short enough that pausing (e.g. to enroll someone) reacts quickly. */
private const val LISTEN_SLICE_MS = 4000
/** How often to retry opening the device if it's not found (e.g. not plugged in yet). */
private const val REOPEN_RETRY_MS = 4000L

/** Simple system beeps via ToneGenerator — no bundled audio assets required. */
private object KioskSound {
    fun playSuccess() = runCatching {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tg.startTone(ToneGenerator.TONE_PROP_ACK, 200)
        tg.release()
    }
    fun playError() = runCatching {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tg.startTone(ToneGenerator.TONE_PROP_NACK, 350)
        tg.release()
    }
}

/**
 * Owns the continuous background fingerprint loop for kiosk mode: opens the
 * USB scanner once, keeps it open, and repeatedly waits for a finger the
 * entire time the app is running — no button ever needs to be pressed.
 *
 * [paused] should be true only while another screen needs exclusive access to
 * the scanner (currently: [EnrollFingerprintScreen]), since the USB device can
 * only be held open by one connection at a time. The loop releases the device
 * while paused and reopens it automatically the moment it's unpaused.
 *
 * On a resolved scan (matched or not), [onResolved] is invoked so the caller
 * can force navigation back to the dashboard, per the kiosk spec.
 */
@Composable
fun rememberKioskState(
    activity: ComponentActivity,
    members: List<Member>,
    paused: Boolean,
    onResolved: () -> Unit
): State<Pair<KioskPhase, Member?>> {
    val scanner = remember { FingerprintScanner(activity) }
    val phase = remember { mutableStateOf(KioskPhase.IDLE) }
    val matchedMember = remember { mutableStateOf<Member?>(null) }
    val currentPaused by rememberUpdatedState(paused)
    val currentMembers by rememberUpdatedState(members)
    val currentOnResolved by rememberUpdatedState(onResolved)

    DisposableEffect(Unit) { onDispose { scanner.close() } }

    LaunchedEffect(Unit) {
        var deviceOpen = false
        while (isActive) {
            if (currentPaused) {
                if (deviceOpen) {
                    scanner.close()
                    deviceOpen = false
                }
                delay(300)
                continue
            }

            if (!deviceOpen) {
                deviceOpen = scanner.open() is FingerprintScanner.OpenResult.Success
                if (!deviceOpen) {
                    delay(REOPEN_RETRY_MS)
                    continue
                }
            }

            when (val capture = scanner.captureTemplate(timeoutMs = LISTEN_SLICE_MS)) {
                is FingerprintScanner.CaptureResult.Success -> {
                    val enrolled = currentMembers.filter { it.fingerprintTemplate != null }
                    var matched: Member? = null
                    for (m in enrolled) {
                        if (scanner.match(m.fingerprintTemplate!!, capture.template)) {
                            matched = m
                            break
                        }
                    }
                    if (matched != null) {
                        val expired = statusOf(matched.expiryMillis) == MemberStatus.EXPIRED
                        matchedMember.value = matched
                        phase.value = if (expired) KioskPhase.MEMBER_EXPIRED else KioskPhase.MEMBER_ACTIVE
                        KioskSound.playSuccess()
                        delay(MATCHED_DISPLAY_MS)
                    } else {
                        phase.value = KioskPhase.NOT_RECOGNIZED
                        KioskSound.playError()
                        delay(NOT_RECOGNIZED_DISPLAY_MS)
                    }
                    phase.value = KioskPhase.IDLE
                    matchedMember.value = null
                    currentOnResolved()
                }
                FingerprintScanner.CaptureResult.Timeout -> {
                    // No finger placed during this slice — keep listening silently.
                }
                is FingerprintScanner.CaptureResult.Error -> {
                    // Likely the device was unplugged mid-loop; drop the handle and retry opening.
                    deviceOpen = false
                    delay(1000)
                }
            }
        }
    }

    return remember { derivedStateOf { phase.value to matchedMember.value } }
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
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (phase) {
                    KioskPhase.MEMBER_ACTIVE, KioskPhase.MEMBER_EXPIRED -> {
                        val photoFile = member?.photoPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                        Box(
                            modifier = Modifier.size(140.dp).clip(CircleShape).background(GymColors.Surface),
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, tint = GymColors.Success, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Member Active", color = GymColors.Success, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Close, null, tint = GymColors.Danger, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(10.dp))
                                Text("Membership Expired", color = GymColors.Danger, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
    Icon(Icons.Filled.Person, null, tint = GymColors.TextFaint, modifier = Modifier.size(64.dp))
}
