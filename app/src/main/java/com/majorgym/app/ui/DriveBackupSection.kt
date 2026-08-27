package com.majorgym.app.ui

import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.services.drive.model.File as DriveFile
import com.majorgym.app.MembersViewModel
import com.majorgym.app.data.DriveBackupHistoryEntry
import com.majorgym.app.data.DriveBackupPrefs
import com.majorgym.app.data.DriveBackupStatus
import com.majorgym.app.data.DriveResult
import com.majorgym.app.data.formatBackupSize
import com.majorgym.app.data.formatDate
import com.majorgym.app.data.formatDateTime
import com.majorgym.app.data.formatTimeOfDay

private fun sectionCardModifier() = Modifier
    .fillMaxWidth()
    .border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GymColors.SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = sectionCardModifier()
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
private fun CardHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = GymColors.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = GymColors.Text, fontWeight = FontWeight.Bold)
    }
}

private fun minutesToLabel(minutes: Int): String {
    val h24 = minutes / 60
    val m = minutes % 60
    val amPm = if (h24 < 12) "AM" else "PM"
    val h12 = when (val h = h24 % 12) { 0 -> 12; else -> h }
    return "%d:%02d %s".format(h12, m, amPm)
}

private fun statusColor(status: DriveBackupStatus): Color = when (status) {
    DriveBackupStatus.SUCCESS -> GymColors.Success
    DriveBackupStatus.FAILED, DriveBackupStatus.AUTH_EXPIRED -> GymColors.Danger
    DriveBackupStatus.OFFLINE_PENDING -> GymColors.Warning
    DriveBackupStatus.NONE -> GymColors.TextMuted
}

private fun statusLabel(status: DriveBackupStatus, error: String?): String = when (status) {
    DriveBackupStatus.SUCCESS -> "Backup uploaded successfully"
    DriveBackupStatus.FAILED -> "Backup failed" + (error?.let { " — $it" } ?: "")
    DriveBackupStatus.AUTH_EXPIRED -> "Google Drive disconnected"
    DriveBackupStatus.OFFLINE_PENDING -> "Backup pending — internet connection unavailable"
    DriveBackupStatus.NONE -> "No backup yet"
}

private fun statusIcon(status: DriveBackupStatus): androidx.compose.ui.graphics.vector.ImageVector = when (status) {
    DriveBackupStatus.SUCCESS -> Icons.Filled.CheckCircle
    DriveBackupStatus.FAILED, DriveBackupStatus.AUTH_EXPIRED -> Icons.Filled.Error
    DriveBackupStatus.OFFLINE_PENDING -> Icons.Filled.WarningAmber
    DriveBackupStatus.NONE -> Icons.Filled.Info
}

/**
 * The new Google Drive automatic backup UI (spec sections 2, 8-15). This is
 * meant to be placed inside the EXISTING Backup screen's scrollable content,
 * alongside (not replacing) the existing manual Export/Restore/Share cards.
 */
@Composable
fun DriveBackupSection(vm: MembersViewModel) {
    val context = LocalContext.current
    val state by vm.driveBackup.state.collectAsState()

    var actionMessage by remember { mutableStateOf<String?>(null) }
    var showRestorePicker by remember { mutableStateOf(false) }
    var showAllHistory by remember { mutableStateOf(false) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var historyActionEntry by remember { mutableStateOf<DriveBackupHistoryEntry?>(null) }
    var confirmRestoreEntry by remember { mutableStateOf<DriveBackupHistoryEntry?>(null) }
    var confirmRestoreDriveFile by remember { mutableStateOf<DriveFile?>(null) }

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data: Intent? = result.data
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        val account = runCatching { task.getResult(com.google.android.gms.common.api.ApiException::class.java) }.getOrNull()
        if (account != null && account.email != null) {
            vm.driveBackup.onAccountConnected(account)
            actionMessage = "Google Drive connected."
        } else {
            actionMessage = "Google sign-in was cancelled."
        }
    }

    Spacer(Modifier.height(20.dp))
    Text("AUTOMATIC BACKUP", color = GymColors.TextMuted, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.5.sp)
    Spacer(Modifier.height(10.dp))

    // ---- Google Drive connection ----
    SectionCard {
        CardHeading(Icons.Filled.Cloud, "Google Drive")
        Spacer(Modifier.height(10.dp))
        if (state.isConnected) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.CloudDone, null, tint = GymColors.Success, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Connected to", color = GymColors.TextMuted, fontSize = 11.sp)
                    Text(state.connectedEmail ?: "", color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        vm.driveBackup.beginChangeAccount()
                        signInLauncher.launch(vm.driveBackup.signInClient().signInIntent)
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text("Change Account", color = GymColors.Text, fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        vm.driveBackup.disconnect()
                        actionMessage = "Google Drive disconnected."
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GymColors.Danger),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) { Text("Disconnect", fontSize = 12.sp) }
            }
        } else {
            Text(
                "Connect your own Google account to automatically back up gym records to Drive.",
                color = GymColors.TextMuted, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { signInLauncher.launch(vm.driveBackup.signInClient().signInIntent) },
                colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) { Text("Connect Google Drive", fontWeight = FontWeight.Bold, color = Color.Black) }
        }
    }

    Spacer(Modifier.height(14.dp))

    // ---- Automatic backup toggle + time ----
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            CardHeading(Icons.Filled.Schedule, "Automatic Backup")
            Switch(
                checked = state.autoBackupEnabled,
                onCheckedChange = { vm.driveBackup.setAutoBackupEnabled(it) },
                enabled = state.isConnected,
                colors = SwitchDefaults.colors(checkedTrackColor = GymColors.Accent)
            )
        }
        if (!state.isConnected) {
            Spacer(Modifier.height(6.dp))
            Text("Connect Google Drive first to enable automatic backups.", color = GymColors.TextFaint, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GymColors.Surface2)
                .clickable(enabled = state.autoBackupEnabled) {
                    val h = state.backupTimeMinutes / 60
                    val m = state.backupTimeMinutes % 60
                    TimePickerDialog(context, { _, hour, minute ->
                        vm.driveBackup.setBackupTimeMinutes(hour * 60 + minute)
                    }, h, m, false).show()
                }
                .padding(12.dp)
        ) {
            Text("Backup Time", color = GymColors.Text, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(minutesToLabel(state.backupTimeMinutes), color = GymColors.TextMuted, fontSize = 13.sp)
                Icon(Icons.Filled.ChevronRight, null, tint = GymColors.TextFaint, modifier = Modifier.size(18.dp))
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    // ---- Backup status + Backup Now ----
    SectionCard {
        CardHeading(Icons.Filled.CloudUpload, "Backup Status")
        Spacer(Modifier.height(10.dp))
        StatusRowLine("Last Backup", if (state.lastBackupMillis > 0) "${formatDate(state.lastBackupMillis)} \u2022 ${formatTimeOfDay(state.lastBackupMillis)}" else "No backup yet")
        StatusRowLine("Google Drive", if (state.isConnected) "Connected" else "Not connected")
        if (state.lastBackupMillis > 0) StatusRowLine("Backup Size", formatBackupSize(state.lastBackupSizeBytes))
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Status", color = GymColors.TextFaint, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.weight(1.4f)) {
                Icon(statusIcon(state.lastBackupStatus), null, tint = statusColor(state.lastBackupStatus), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    statusLabel(state.lastBackupStatus, state.lastBackupError),
                    color = statusColor(state.lastBackupStatus), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
        if (state.autoBackupEnabled && state.nextBackupMillis > 0) {
            StatusRowLine("Next Backup", "${formatDate(state.nextBackupMillis)} \u2022 ${formatTimeOfDay(state.nextBackupMillis)}")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                vm.driveBackupNow { result ->
                    actionMessage = when (result) {
                        is DriveResult.Ok<*> -> "Backup completed successfully."
                        is DriveResult.Err -> if (result.authExpired) "Google Drive disconnected. Please reconnect."
                            else "Backup failed: ${result.message}"
                    }
                }
            },
            enabled = state.isConnected && !state.isBackingUp,
            colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent, disabledContainerColor = GymColors.Surface2),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Text(
                if (state.isBackingUp) "Backing up\u2026" else "BACKUP NOW",
                fontWeight = FontWeight.Bold, color = Color.Black
            )
        }
        if (state.lastBackupStatus == DriveBackupStatus.AUTH_EXPIRED) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { signInLauncher.launch(vm.driveBackup.signInClient().signInIntent) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(40.dp)
            ) { Text("Reconnect Google Drive", fontSize = 12.sp) }
        }
        actionMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = GymColors.TextMuted, fontSize = 11.sp)
        }
    }

    Spacer(Modifier.height(14.dp))

    // ---- Backup history ----
    SectionCard {
        CardHeading(Icons.Filled.History, "Backup History")
        Spacer(Modifier.height(10.dp))
        if (state.history.isEmpty()) {
            Text("No backups yet.", color = GymColors.TextFaint, fontSize = 12.sp)
        } else {
            val visible = if (showAllHistory) state.history else state.history.take(3)
            visible.forEach { entry ->
                HistoryRow(entry, onClick = { if (entry.driveFileId != null) historyActionEntry = entry })
                Spacer(Modifier.height(8.dp))
            }
            if (state.history.size > 3 && !showAllHistory) {
                TextButton(onClick = { showAllHistory = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("VIEW ALL", color = GymColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    // ---- Restore ----
    SectionCard {
        CardHeading(Icons.Filled.Restore, "Restore")
        Spacer(Modifier.height(6.dp))
        Text("Browse and restore a backup stored in your connected Google Drive.", color = GymColors.TextMuted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { showRestorePicker = true },
            enabled = state.isConnected,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) { Text("RESTORE BACKUP", color = GymColors.Text, fontWeight = FontWeight.SemiBold) }
    }

    Spacer(Modifier.height(14.dp))

    // ---- Retention ----
    SectionCard {
        CardHeading(Icons.Filled.Storage, "Backup Settings")
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(GymColors.Surface2)
                .clickable { showRetentionDialog = true }
                .padding(12.dp)
        ) {
            Text("Keep Backups For", color = GymColors.Text, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DriveBackupPrefs.RETENTION_OPTIONS.firstOrNull { it.first == state.retentionDays }?.second ?: "30 Days",
                    color = GymColors.TextMuted, fontSize = 13.sp
                )
                Icon(Icons.Filled.ChevronRight, null, tint = GymColors.TextFaint, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Backups older than this are automatically removed from the Major Gym Backups folder only.",
            color = GymColors.TextFaint, fontSize = 11.sp
        )
    }

    // ---- Dialogs ----
    if (showRetentionDialog) {
        AlertDialog(
            onDismissRequest = { showRetentionDialog = false },
            title = { Text("Keep Backups For", color = GymColors.Text) },
            text = {
                Column {
                    DriveBackupPrefs.RETENTION_OPTIONS.forEach { (days, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.driveBackup.setRetentionDays(days)
                                    showRetentionDialog = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            RadioButton(selected = state.retentionDays == days, onClick = {
                                vm.driveBackup.setRetentionDays(days)
                                showRetentionDialog = false
                            })
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = GymColors.Text, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRetentionDialog = false }) { Text("Close") } },
            containerColor = GymColors.SurfaceCard
        )
    }

    historyActionEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { historyActionEntry = null },
            title = { Text(entry.fileName, color = GymColors.Text, fontSize = 14.sp) },
            text = {
                Column {
                    Text(
                        "${formatDate(entry.timestampMillis)} \u2022 ${formatTimeOfDay(entry.timestampMillis)} \u00B7 ${formatBackupSize(entry.sizeBytes)}",
                        color = GymColors.TextMuted, fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestoreEntry = entry
                    historyActionEntry = null
                }) { Text("Restore", color = GymColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val id = entry.driveFileId
                    historyActionEntry = null
                    if (id != null) {
                        vm.driveDeleteRemoteBackup(id) { result ->
                            actionMessage = if (result is DriveResult.Ok) "Backup deleted from Drive."
                            else "Could not delete backup."
                        }
                    }
                }) { Text("Delete from Drive", color = GymColors.Danger) }
            },
            containerColor = GymColors.SurfaceCard
        )
    }

    confirmRestoreEntry?.let { entry ->
        RestoreConfirmDialog(
            onConfirm = {
                val id = entry.driveFileId
                confirmRestoreEntry = null
                if (id != null) {
                    vm.driveRestoreBackup(id) { result ->
                        actionMessage = when (result) {
                            is DriveResult.Ok -> "Restored ${result.value} member records."
                            is DriveResult.Err -> "Restore failed: ${result.message}"
                        }
                    }
                }
            },
            onDismiss = { confirmRestoreEntry = null }
        )
    }

    confirmRestoreDriveFile?.let { file ->
        RestoreConfirmDialog(
            onConfirm = {
                val id = file.id
                confirmRestoreDriveFile = null
                showRestorePicker = false
                vm.driveRestoreBackup(id) { result ->
                    actionMessage = when (result) {
                        is DriveResult.Ok -> "Restored ${result.value} member records."
                        is DriveResult.Err -> "Restore failed: ${result.message}"
                    }
                }
            },
            onDismiss = { confirmRestoreDriveFile = null }
        )
    }

    if (showRestorePicker) {
        RestorePickerDialog(
            vm = vm,
            onPick = { file -> confirmRestoreDriveFile = file },
            onDismiss = { showRestorePicker = false }
        )
    }
}

@Composable
private fun StatusRowLine(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(label, color = GymColors.TextFaint, fontSize = 12.sp)
        Text(value, color = GymColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HistoryRow(entry: DriveBackupHistoryEntry, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(GymColors.Surface2)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column {
            Text("${formatDate(entry.timestampMillis)}", color = GymColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                if (entry.status == DriveBackupStatus.SUCCESS) formatBackupSize(entry.sizeBytes) else (entry.errorMessage ?: "Failed"),
                color = GymColors.TextFaint, fontSize = 11.sp
            )
        }
        Icon(statusIcon(entry.status), null, tint = statusColor(entry.status), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RestoreConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore this backup?", color = GymColors.Text) },
        text = {
            Text(
                "Restoring this backup will replace the current app data. A safety backup of your current data will be created first.",
                color = GymColors.TextMuted, fontSize = 13.sp
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Restore", color = GymColors.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = GymColors.TextMuted) } },
        containerColor = GymColors.SurfaceCard
    )
}

@Composable
private fun RestorePickerDialog(vm: MembersViewModel, onPick: (DriveFile) -> Unit, onDismiss: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<DriveFile>>(emptyList()) }

    LaunchedEffect(Unit) {
        vm.driveListRemoteBackups { result ->
            loading = false
            when (result) {
                is DriveResult.Ok -> files = result.value
                is DriveResult.Err -> error = if (result.authExpired) "Google Drive disconnected." else result.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select a backup", color = GymColors.Text) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GymColors.Accent)
                    }
                    error != null -> Text(error ?: "", color = GymColors.Danger, fontSize = 13.sp)
                    files.isEmpty() -> Text("No backups available in Drive yet.", color = GymColors.TextFaint, fontSize = 13.sp)
                    else -> files.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(f) }
                                .padding(vertical = 10.dp)
                        ) {
                            Icon(Icons.Filled.Description, null, tint = GymColors.Accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(f.name ?: "backup.json", color = GymColors.Text, fontSize = 12.sp)
                                Text(
                                    "${f.createdTime?.let { formatDateTime(it.value) } ?: ""} \u00B7 ${formatBackupSize(f.getSize() ?: 0L)}",
                                    color = GymColors.TextFaint, fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = GymColors.SurfaceCard
    )
}
