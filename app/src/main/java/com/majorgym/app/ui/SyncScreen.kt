package com.majorgym.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import com.majorgym.app.data.DashboardCard
import com.majorgym.app.data.DashboardPrivacyPrefs
import com.majorgym.app.data.SyncOutcome
import com.majorgym.app.data.formatDate

@Composable
fun SyncScreen(vm: MembersViewModel) {
    var deviceName by remember { mutableStateOf(vm.deviceName()) }
    var codeInput by remember { mutableStateOf(vm.syncCode() ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(vm.pairedDevices()) }

    // Relocation: the Dashboard Number Visibility (gear) and Dashboard
    // Privacy (eye) controls used to live in the Dashboard header - they now
    // live here instead. Exact same DashboardPrivacyPrefs storage, click
    // handlers, and settings dialog as before - only their screen changed,
    // so every existing choice is preserved and Dashboard's own behavior
    // (blank privacy view, per-card number visibility) is unaffected.
    val context = LocalContext.current
    val privacyPrefs = remember { DashboardPrivacyPrefs(context) }
    var masterPrivacyOn by remember { mutableStateOf(privacyPrefs.masterPrivacyOn) }
    var showCardSettings by remember { mutableStateOf(false) }
    var totalVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.TOTAL)) }
    var activeVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.ACTIVE)) }
    var expiringVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.EXPIRING)) }
    var expiredVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.EXPIRED)) }
    var holdVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.HOLD)) }
    var dueVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.DUE)) }

    // Fix #3: the screen must stay usable once the keyboard opens for the
    // device-name/Sync Code fields - verticalScroll lets the whole page
    // scroll (same pattern BackupScreen already uses) and imePadding adds
    // bottom space for the keyboard itself, so content/buttons below the
    // focused field are never permanently hidden behind it. No layout,
    // color, or Sync logic changes beyond this.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 90.dp)
            .imePadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "DEVICE SYNC", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = 0.5.sp,
                modifier = Modifier.weight(1f)
            )
            // Relocated from the Dashboard header (same icon, click handler,
            // and DashboardPrivacyPrefs storage) - opens the same per-card
            // number-visibility settings dialog as before.
            if (!masterPrivacyOn) {
                IconButton(onClick = { showCardSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Dashboard number visibility settings", tint = GymColors.TextMuted)
                }
            }
            // Relocated from the Dashboard header (same icon, click handler,
            // and DashboardPrivacyPrefs storage) - still controls the exact
            // same Dashboard Privacy Mode.
            IconButton(onClick = {
                masterPrivacyOn = !masterPrivacyOn
                privacyPrefs.masterPrivacyOn = masterPrivacyOn
            }) {
                Icon(
                    if (masterPrivacyOn) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (masterPrivacyOn) "Privacy mode on - tap to show Dashboard" else "Privacy mode off - tap to hide Dashboard",
                    tint = if (masterPrivacyOn) GymColors.Accent else GymColors.TextMuted
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = GymColors.SurfaceCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("This Device", color = GymColors.Text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it; vm.setDeviceName(it) },
                    label = { Text("Device name", color = GymColors.TextFaint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = gymFieldColors()
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = GymColors.SurfaceCard),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Sync Circle Code", color = GymColors.Text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                    label = { Text("Sync code", color = GymColors.TextFaint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = gymFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            codeInput = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
                            vm.setSyncCode(codeInput)
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) { Text("Generate New", color = GymColors.Text, fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = { vm.setSyncCode(codeInput) },
                        enabled = codeInput.length >= 4,
                        colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent, disabledContainerColor = GymColors.Surface2),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) { Text("Save Code", fontWeight = FontWeight.Bold, color = if (codeInput.length >= 4) Color.Black else GymColors.TextFaint) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        val syncInteractionSource = remember { MutableInteractionSource() }
        Button(
            onClick = {
                syncing = true
                status = "Starting sync\u2026"
                vm.startSync(
                    onStatus = { status = it },
                    onDone = { outcome ->
                        syncing = false
                        status = when (outcome) {
                            is SyncOutcome.Success -> "Synced with ${outcome.peerName} \u2014 ${outcome.recordCount} record(s) merged."
                            SyncOutcome.NoCodeSet -> "Set a sync code first."
                            SyncOutcome.NotFound -> "No authorized device found. Make sure all phones are on the same Wi-Fi or hotspot, have the same sync code, and have this Sync screen open."
                            is SyncOutcome.Error -> "Sync failed: ${outcome.message}"
                        }
                        paired = vm.pairedDevices()
                    }
                )
            },
            enabled = !syncing,
            colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent, disabledContainerColor = GymColors.Surface2),
            shape = RoundedCornerShape(12.dp),
            interactionSource = syncInteractionSource,
            modifier = Modifier.fillMaxWidth().height(50.dp).gymPressScale(syncInteractionSource)
        ) {
            // Section 16: idle <-> working content swaps with a short cross-fade
            // instead of an instant replace, communicating idle -> working ->
            // completed without changing what the button actually does.
            AnimatedContent(
                targetState = syncing,
                transitionSpec = { fadeIn(GymMotion.standardTween()) togetherWith fadeOut(GymMotion.fastTween()) },
                label = "syncButtonContent"
            ) { isSyncing ->
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Sync, null, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("Sync Now", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 15.sp)
                    }
                }
            }
        }

        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = GymColors.TextMuted, fontSize = 12.sp)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "PAIRED DEVICES (${paired.size})",
            color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
        )
        Spacer(Modifier.height(8.dp))
        if (paired.isEmpty()) {
            Text("No other devices synced yet.", color = GymColors.TextFaint, fontSize = 12.sp)
        } else {
            paired.forEach { d ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GymColors.SurfaceCard)
                        .border(1.dp, GymColors.Border, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(d.name, color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("Last synced ${formatDate(d.lastSyncedMillis)}", color = GymColors.TextMuted, fontSize = 11.sp)
                }
            }
        }
    }

    // Relocated from the Dashboard header - same dialog, same
    // DashboardVisibilityRow rows, same DashboardPrivacyPrefs writes as
    // before, just opened from here now.
    if (showCardSettings) {
        AlertDialog(
            onDismissRequest = { showCardSettings = false },
            containerColor = GymColors.SurfaceCard,
            title = { Text("Dashboard Number Visibility", color = GymColors.Text, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Each card's number can be shown or hidden independently. The card itself always stays visible and tappable.",
                        color = GymColors.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)
                    )
                    DashboardVisibilityRow("Total Members", totalVisible) {
                        totalVisible = it; privacyPrefs.setNumberVisible(DashboardCard.TOTAL, it)
                    }
                    DashboardVisibilityRow("Active Members", activeVisible) {
                        activeVisible = it; privacyPrefs.setNumberVisible(DashboardCard.ACTIVE, it)
                    }
                    DashboardVisibilityRow("Expiring Soon", expiringVisible) {
                        expiringVisible = it; privacyPrefs.setNumberVisible(DashboardCard.EXPIRING, it)
                    }
                    DashboardVisibilityRow("Expired Members", expiredVisible) {
                        expiredVisible = it; privacyPrefs.setNumberVisible(DashboardCard.EXPIRED, it)
                    }
                    DashboardVisibilityRow("Hold Members", holdVisible) {
                        holdVisible = it; privacyPrefs.setNumberVisible(DashboardCard.HOLD, it)
                    }
                    DashboardVisibilityRow("Due Members", dueVisible) {
                        dueVisible = it; privacyPrefs.setNumberVisible(DashboardCard.DUE, it)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCardSettings = false }) { Text("Done", color = GymColors.Accent) }
            }
        )
    }
}

