package com.majorgym.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majorgym.app.MembersViewModel
import com.majorgym.app.data.SyncOutcome
import com.majorgym.app.data.SyncPrefs
import com.majorgym.app.data.formatDate

@Composable
fun SyncScreen(vm: MembersViewModel) {
    var deviceName by remember { mutableStateOf(vm.deviceName()) }
    var codeInput by remember { mutableStateOf(vm.syncCode() ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var paired by remember { mutableStateOf(vm.pairedDevices()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 90.dp)
    ) {
        Text("DEVICE SYNC", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "Sync members between up to 3 authorized phones over the same Wi-Fi or hotspot. No internet, no cloud account needed.",
            color = GymColors.TextMuted, fontSize = 13.sp
        )
        Spacer(Modifier.height(20.dp))

        Card(colors = CardDefaults.cardColors(containerColor = GymColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("This Device", color = GymColors.Text, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it; vm.setDeviceName(it) },
                    label = { Text("Device name", color = GymColors.TextFaint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = gymFieldColors()
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        Card(colors = CardDefaults.cardColors(containerColor = GymColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Sync Circle Code", color = GymColors.Text, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Set this once on the first phone, then enter the exact same code on up to 2 other phones. Only devices sharing this code can join this gym's sync circle.",
                    color = GymColors.TextMuted, fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) },
                    label = { Text("Sync code", color = GymColors.TextFaint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = gymFieldColors()
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            codeInput = (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")
                            vm.setSyncCode(codeInput)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Generate New", color = GymColors.Text) }
                    Button(
                        onClick = { vm.setSyncCode(codeInput) },
                        enabled = codeInput.length >= 4,
                        colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent, disabledContainerColor = GymColors.Surface2),
                        modifier = Modifier.weight(1f)
                    ) { Text("Save Code") }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

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
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (syncing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text("Sync Now", fontWeight = FontWeight.Bold)
            }
        }

        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = GymColors.TextMuted, fontSize = 12.sp)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "PAIRED DEVICES (${paired.size}/${SyncPrefs.MAX_OTHER_DEVICES})",
            color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium
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
                        .clip(RoundedCornerShape(16.dp))
                        .background(GymColors.Surface)
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
}
