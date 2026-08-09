package com.majorgym.app.ui

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
import kotlinx.coroutines.launch

/** Enroll/check-in status text used only by the enrollment flow now (kiosk mode
 *  has its own separate state machine in KioskOverlay.kt). */
private enum class ScanStatus { IDLE, OPENING, WAITING, CAPTURED, MATCHED, NOT_MATCHED, FAILED }

private fun statusText(status: ScanStatus, detail: String): String = when (status) {
    ScanStatus.IDLE -> "Ready to scan"
    ScanStatus.OPENING -> "Connecting to scanner\u2026"
    ScanStatus.WAITING -> "Place finger on the scanner\u2026"
    ScanStatus.CAPTURED -> "Captured"
    ScanStatus.MATCHED -> "Match confirmed"
    ScanStatus.NOT_MATCHED -> "Fingerprint did not match"
    ScanStatus.FAILED -> detail.ifBlank { "Something went wrong" }
}

/**
 * Morning/Evening picker for the enrollment screen (spec: "the owner selects
 * the member's group before/during fingerprint enrollment"). Same pill-toggle
 * visual language as [PlanGrid] in Screens.kt, so it reads as part of the
 * existing design system rather than a new UI pattern.
 */
@Composable
private fun FingerprintGroupSelector(selected: FingerprintGroup, enabled: Boolean, onSelect: (FingerprintGroup) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("FINGERPRINT GROUP", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(FingerprintGroup.MORNING to "Morning", FingerprintGroup.EVENING to "Evening").forEach { (group, label) ->
                val active = group == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) GymColors.Accent else GymColors.SurfaceCard)
                        .border(1.dp, if (active) GymColors.Accent else GymColors.Border, RoundedCornerShape(10.dp))
                        .clickable(enabled = enabled) { onSelect(group) }
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(label, color = if (active) Color.Black else GymColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
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
    // Defaults to the member's existing group if re-enrolling, otherwise to
    // whichever group is "current" right now — purely a convenience default,
    // the owner can still pick either one before scanning (spec section 1).
    var selectedGroup by remember {
        mutableStateOf(
            FingerprintGroup.fromStorageValue(member.fingerprintGroup).let {
                if (it == FingerprintGroup.UNASSIGNED) FingerprintGroupConfig.currentGroup() else it
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // scanner.close() is already fully defensive (no-ops safely when no
            // device was ever found/opened), but this belt-and-braces catch makes
            // sure nothing thrown here can ever take the whole screen transition
            // down with it.
            runCatching { scanner.close() }
        }
    }

    // Handles both the on-screen ← arrow (below) and the hardware/gesture back
    // button. Without this, system back has no "previous screen" to return to on
    // this manual-navigation app and falls through to closing the Activity — this
    // makes it behave identically to tapping ← Back.
    BackHandler(enabled = true) { onNavigate(returnTo) }

    fun runScan() {
        scope.launch {
            status = ScanStatus.OPENING
            when (val open = scanner.open()) {
                is FingerprintScanner.OpenResult.Success -> {}
                FingerprintScanner.OpenResult.DeviceNotFound -> {
                    status = ScanStatus.FAILED; detail = "No SecuGen scanner found. Plug it in via USB and try again."
                    return@launch
                }
                FingerprintScanner.OpenResult.PermissionDenied -> {
                    status = ScanStatus.FAILED; detail = "USB permission was denied for the scanner."
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
                    val prior = firstScan
                    if (prior == null) {
                        // First of the two required scans.
                        firstScan = capture.template
                        status = ScanStatus.IDLE
                        detail = "First scan captured. Scan the same finger again to confirm."
                    } else {
                        val matched = scanner.match(prior, capture.template)
                        if (matched) {
                            vm.saveFingerprintTemplate(member, capture.template, selectedGroup)
                            status = ScanStatus.MATCHED
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
                    status = ScanStatus.FAILED; detail = "Capture error (${capture.code})."
                }
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
                    .clickable(enabled = !done) { runScan() },
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
                Spacer(Modifier.height(24.dp))
                FingerprintGroupSelector(
                    selected = selectedGroup,
                    // Only relevant before the pair of confirmation scans starts —
                    // switching mid-confirmation would be confusing, so lock it in
                    // once the first scan is captured.
                    enabled = firstScan == null && status != ScanStatus.OPENING && status != ScanStatus.WAITING,
                    onSelect = { selectedGroup = it }
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { runScan() },
                    enabled = status != ScanStatus.OPENING && status != ScanStatus.WAITING,
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


