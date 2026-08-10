package com.majorgym.app.ui

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majorgym.app.MembersViewModel
import com.majorgym.app.Screen
import com.majorgym.app.data.*
import com.majorgym.app.kiosk.FingerprintKioskService
import com.majorgym.app.kiosk.ScannerOwnership
import kotlinx.coroutines.launch

private const val TAG = "EnrollFingerprint"

/** How long to wait for the background kiosk scanner to actually release the
 *  USB device before giving up and trying to open anyway. This is the piece
 *  that was missing before: enrollment used to open its own scanner the
 *  instant the user tapped Scan, with no guarantee the background loop (which
 *  can be mid-capture on the same physical device) had let go of it yet. */
private const val RELEASE_WAIT_MS = 5000L

/** Enroll/check-in status text used only by the enrollment flow now (kiosk mode
 *  has its own separate state machine in KioskOverlay.kt). */
private enum class ScanStatus { IDLE, RELEASING, OPENING, WAITING, CAPTURED, MATCHED, NOT_MATCHED, FAILED }

private fun statusText(status: ScanStatus, detail: String): String = when (status) {
    ScanStatus.IDLE -> "Ready to scan"
    ScanStatus.RELEASING -> "Stopping background scanner\u2026"
    ScanStatus.OPENING -> "Connecting to scanner\u2026"
    ScanStatus.WAITING -> "Place finger on the scanner\u2026"
    ScanStatus.CAPTURED -> "Captured"
    ScanStatus.MATCHED -> "Match confirmed"
    ScanStatus.NOT_MATCHED -> "Fingerprint did not match"
    ScanStatus.FAILED -> detail.ifBlank { "Something went wrong" }
}

/**
 * Enroll (or re-enroll) [member]'s fingerprint on a connected SecuGen USB
 * scanner. Two consecutive good scans of the same finger are required and
 * matched against each other before saving, so a smudged single read never
 * silently becomes the stored template.
 */
@Composable
fun EnrollFingerprintScreen(member: Member, vm: MembersViewModel, returnTo: Screen, onNavigate: (Screen) -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    val scanner = remember { FingerprintScanner(activity) }
    var status by remember { mutableStateOf(ScanStatus.IDLE) }
    var detail by remember { mutableStateOf("") }
    var firstScan by remember { mutableStateOf<ByteArray?>(null) }
    var done by remember { mutableStateOf(false) }
    // Guards against a second tap starting a new scan while one is already
    // running — the button is disabled during OPENING/WAITING, but this also
    // covers the RELEASING wait before that state is even set.
    var scanInFlight by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        // Tell the background kiosk loop to stop the moment this screen is
        // entered — don't wait for the first tap on Scan. MainActivity/
        // KioskOverlay also does this reactively via `paused`, but doing it
        // here too means the release clock starts as early as possible.
        FingerprintKioskService.requestStop(activity)
        onDispose {
            // scanner.close() is already fully defensive (no-ops safely when no
            // device was ever found/opened, and safe even if a capture is still
            // in flight — see FingerprintScanner), but this belt-and-braces catch
            // makes sure nothing thrown here can ever take the screen transition
            // down with it.
            runCatching { scanner.close() }
            ScannerOwnership.release(ScannerOwnership.Owner.ENROLLMENT)
            // Let the background scanner resume now that we're done with the
            // device — otherwise it would sit idle until the next poll tick.
            FingerprintKioskService.requestStart(activity)
        }
    }

    // Handles both the on-screen ← arrow (below) and the hardware/gesture back
    // button. Without this, system back has no "previous screen" to return to on
    // this manual-navigation app and falls through to closing the Activity — this
    // makes it behave identically to tapping ← Back.
    BackHandler(enabled = true) { onNavigate(returnTo) }

    fun runScan() {
        if (scanInFlight) return
        scanInFlight = true
        scope.launch {
            // Wrapping the whole flow: any unexpected exception (SDK-level or
            // otherwise) lands here instead of propagating up and taking the
            // screen/Activity down with it — which is what used to send the app
            // back to Home whenever a finger touched the scanner mid-conflict.
            try {
                status = ScanStatus.RELEASING
                detail = ""
                FingerprintKioskService.requestStop(activity)
                val released = ScannerOwnership.awaitReleased(RELEASE_WAIT_MS)
                if (!released) {
                    Log.w(TAG, "SCANNER_OPEN_FAILED background scanner did not release in time, trying anyway")
                }

                status = ScanStatus.OPENING
                Log.d(TAG, "SCANNER_ENROLL_START")
                when (val open = scanner.open()) {
                    is FingerprintScanner.OpenResult.Success -> {
                        ScannerOwnership.acquire(ScannerOwnership.Owner.ENROLLMENT)
                    }
                    FingerprintScanner.OpenResult.DeviceNotFound -> {
                        status = ScanStatus.FAILED; detail = "No SecuGen scanner found. Plug it in via USB and try again."
                        return@launch
                    }
                    FingerprintScanner.OpenResult.PermissionDenied -> {
                        status = ScanStatus.FAILED; detail = "USB permission was denied for the scanner."
                        return@launch
                    }
                    FingerprintScanner.OpenResult.Busy -> {
                        status = ScanStatus.FAILED
                        detail = "Fingerprint scanner unavailable. Please reconnect the scanner."
                        return@launch
                    }
                    is FingerprintScanner.OpenResult.Error -> {
                        status = ScanStatus.FAILED; detail = "Scanner error (${open.code})."
                        return@launch
                    }
                }
                status = ScanStatus.WAITING
                when (val capture = scanner.captureTemplate()) {
                    is FingerprintScanner.CaptureResult.Success -> {
                        status = ScanStatus.CAPTURED
                        Log.d(TAG, "SCANNER_TEMPLATE_SUCCESS")
                        val prior = firstScan
                        if (prior == null) {
                            // First of the two required scans.
                            firstScan = capture.template
                            status = ScanStatus.IDLE
                            detail = "First scan captured. Scan the same finger again to confirm."
                        } else {
                            val matched = scanner.match(prior, capture.template)
                            if (matched) {
                                vm.saveFingerprintTemplate(member, capture.template)
                                status = ScanStatus.MATCHED
                                Log.d(TAG, "SCANNER_ENROLL_SUCCESS")
                                done = true
                            } else {
                                status = ScanStatus.NOT_MATCHED
                                detail = "The two scans didn't match. Starting over \u2014 scan the same finger twice."
                                firstScan = null
                            }
                        }
                    }
                    FingerprintScanner.CaptureResult.Timeout -> {
                        status = ScanStatus.FAILED; detail = "No finger detected \u2014 try again."
                    }
                    is FingerprintScanner.CaptureResult.Error -> {
                        Log.w(TAG, "SCANNER_ENROLL_FAILED code=${capture.code}")
                        status = ScanStatus.FAILED; detail = "Capture error (${capture.code})."
                    }
                }
            } catch (e: Throwable) {
                // Never let a scanner exception crash/navigate this screen away.
                // Do NOT make this a fake fix by silently swallowing it: log it
                // with full detail and surface it to the user, but stay put.
                Log.e(TAG, "SCANNER_EXCEPTION in enrollment flow: ${e.message}", e)
                status = ScanStatus.FAILED
                detail = "Scanner error. Please try again."
            } finally {
                scanInFlight = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 20.dp, bottom = 16.dp)
        ) {
            Icon(Icons.Filled.ArrowBack, null, tint = GymColors.Text, modifier = Modifier.clickable { onNavigate(returnTo) })
            Spacer(Modifier.width(10.dp))
            Text("ENROLL FINGERPRINT", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(member.name, color = GymColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(if (done) GymColors.Accent else GymColors.SurfaceCard)
                    .border(2.dp, if (done) GymColors.Accent else GymColors.Border, CircleShape)
                    .clickable(enabled = !done && !scanInFlight) { runScan() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (done) Icons.Filled.CheckCircle else Icons.Filled.Fingerprint,
                    null,
                    tint = if (done) Color.Black else GymColors.Accent,
                    modifier = Modifier.size(64.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                if (done) "Fingerprint enrolled successfully" else statusText(status, detail),
                color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
            )
            if (!done && detail.isNotBlank() && status != ScanStatus.FAILED) {
                Spacer(Modifier.height(6.dp))
                Text(detail, color = GymColors.TextMuted, fontSize = 13.sp)
            }
            if (!done) {
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = { runScan() },
                    enabled = !scanInFlight,
                    colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(if (firstScan == null) "Start Scan" else "Scan Again to Confirm", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }
        }

        Button(
            onClick = { onNavigate(returnTo) },
            modifier = Modifier.fillMaxWidth().height(50.dp).padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (done) GymColors.Accent else GymColors.SurfaceCard),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (done) "Done" else "Cancel", color = if (done) Color.Black else GymColors.TextMuted, fontWeight = FontWeight.Bold)
        }
    }
}


