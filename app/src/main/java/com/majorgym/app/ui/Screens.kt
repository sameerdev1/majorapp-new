package com.majorgym.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.majorgym.app.MembersViewModel
import com.majorgym.app.Screen
import com.majorgym.app.data.*
import java.io.File
import java.time.LocalDate
import java.util.UUID

// ---------- Shared bits ----------

@Composable
fun gymFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = GymColors.Surface2,
    unfocusedContainerColor = GymColors.Surface2,
    focusedTextColor = GymColors.Text,
    unfocusedTextColor = GymColors.Text,
    focusedBorderColor = GymColors.Accent,
    unfocusedBorderColor = GymColors.Border,
    cursorColor = GymColors.Accent,
    focusedPlaceholderColor = GymColors.TextFaint,
    unfocusedPlaceholderColor = GymColors.TextFaint
)

@Composable
fun StatusRing(photoPath: String?, name: String, status: MemberStatus, size: Dp = 56.dp) {
    val color = when (status) {
        MemberStatus.ACTIVE -> GymColors.Success
        MemberStatus.EXPIRING -> GymColors.Warning
        MemberStatus.EXPIRED -> GymColors.Danger
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, color, CircleShape)
            .padding(3.dp)
            .clip(CircleShape)
            .background(GymColors.Surface2),
        contentAlignment = Alignment.Center
    ) {
        if (photoPath != null && File(photoPath).exists()) {
            AsyncImage(
                model = File(photoPath),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Text(
                text = name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString("").uppercase(),
                color = GymColors.TextMuted,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun StatusBadge(status: MemberStatus) {
    val (label, color) = when (status) {
        MemberStatus.ACTIVE -> "Active" to GymColors.Success
        MemberStatus.EXPIRING -> "Expiring Soon" to GymColors.Warning
        MemberStatus.EXPIRED -> "Expired" to GymColors.Danger
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun BottomNav(current: Screen, modifier: Modifier = Modifier, onSelect: (Screen) -> Unit) {
    val items = listOf(
        Triple(Screen.Dashboard as Screen, Icons.Filled.Dashboard, "Dashboard"),
        Triple(Screen.Members as Screen, Icons.Filled.People, "Members"),
        Triple(Screen.Add as Screen, Icons.Filled.PersonAdd, "Add"),
        Triple(Screen.Backup as Screen, Icons.Filled.Storage, "Backup"),
        Triple(Screen.Sync as Screen, Icons.Filled.Sync, "Sync")
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GymColors.Surface)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEach { (screen, icon, label) ->
            val active = current == screen
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(screen) }
            ) {
                Icon(icon, contentDescription = label, tint = if (active) GymColors.Accent else GymColors.TextFaint)
                Text(label, fontSize = 10.sp, color = if (active) GymColors.Accent else GymColors.TextFaint)
            }
        }
    }
}

@Composable
fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(label.uppercase(), color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
fun PlanGrid(selected: String, onSelect: (String) -> Unit) {
    Column {
        for (row in PLAN_MONTHS.keys.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { p ->
                    val active = p == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) GymColors.Accent else GymColors.Surface2)
                            .border(1.dp, if (active) GymColors.Accent else GymColors.Border, RoundedCornerShape(8.dp))
                            .clickable { onSelect(p) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(p, color = if (active) Color.White else GymColors.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
fun DatePickerField(date: LocalDate, onChange: (LocalDate) -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GymColors.Surface2)
            .border(1.dp, GymColors.Border, RoundedCornerShape(8.dp))
            .clickable {
                android.app.DatePickerDialog(
                    context,
                    { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) },
                    date.year, date.monthValue - 1, date.dayOfMonth
                ).show()
            }
            .padding(14.dp)
    ) {
        Text(formatDate(date.toMillis()), color = GymColors.Text)
    }
}

// ---------- Dashboard ----------

@Composable
fun DashboardScreen(members: List<Member>, onNavigate: (Screen) -> Unit) {
    val active = members.count { statusOf(it.expiryMillis) == MemberStatus.ACTIVE }
    val expiring = members.count { statusOf(it.expiryMillis) == MemberStatus.EXPIRING }
    val expired = members.count { statusOf(it.expiryMillis) == MemberStatus.EXPIRED }
    val revenue = members.sumOf { m -> m.historyJson.toHistoryList().sumOf { it.fee } }
    // "Expiring Soon" widget: every member whose membership expires within the next
    // 7 days (inclusive of today and day 7), earliest expiry first. Iterates the full
    // members list every recomposition — `members` itself is backed by a Room Flow, so
    // this recalculates automatically whenever a member is added, edited, deleted, or
    // imported/synced in. Intentionally unbounded: do not add .take()/.first() here.
    val attention = members
        .filter { m ->
            val daysRemaining = daysBetweenNow(m.expiryMillis)
            daysRemaining >= 0 && daysRemaining <= 7
        }
        .sortedBy { it.expiryMillis }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 90.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(GymColors.Accent).padding(6.dp)) {
                    Icon(Icons.Filled.FitnessCenter, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("MAJOR GYM", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Text("Membership overview", color = GymColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
        }
        item {
            GymAttendanceQrCard()
            Spacer(Modifier.height(14.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                StatCard("Total Members", members.size.toString(), Modifier.weight(1f))
                StatCard("Active", active.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                StatCard("Expiring Soon", expiring.toString(), Modifier.weight(1f))
                StatCard("Expired", expired.toString(), Modifier.weight(1f))
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(GymColors.Surface).padding(16.dp)
            ) {
                Text("TOTAL REVENUE", color = GymColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(formatMoney(revenue), color = GymColors.Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
        }
        if (attention.isNotEmpty()) {
            item {
                Text("NEEDS ATTENTION (${attention.size})", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(attention, key = { it.id }) { m ->
                val status = statusOf(m.expiryMillis)
                val days = daysBetweenNow(m.expiryMillis)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(GymColors.Surface)
                        .clickable { onNavigate(Screen.Profile(m.id)) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusRing(m.photoPath, m.name, status, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = GymColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (days < 0) "Expired ${-days}d ago" else "Expires in ${days}d",
                            color = GymColors.TextMuted, fontSize = 11.sp
                        )
                    }
                    StatusBadge(status)
                }
            }
        }
    }
}

/**
 * Permanent attendance QR, always visible on the Dashboard (add-on request).
 * Encodes [QrUtils.GYM_ATTENDANCE_CODE] — one fixed value for the whole gym
 * that never changes, unlike the per-member QR. Members scan this to check in;
 * this card just renders it and lets the owner display it full-size or share
 * the image (e.g. to print, or send to a front-desk tablet).
 */
@Composable
fun GymAttendanceQrCard() {
    val context = LocalContext.current
    var fullScreen by remember { mutableStateOf(false) }
    val qrBitmap = remember { QrUtils.gymQrBitmap(QrUtils.GYM_ATTENDANCE_CODE) }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(GymColors.Surface).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("GYM ATTENDANCE QR", color = GymColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Start))
        Text(
            "Members scan this to check in \u2014 fixed, never changes",
            color = GymColors.TextFaint, fontSize = 10.sp, modifier = Modifier.align(Alignment.Start).padding(top = 2.dp, bottom = 12.dp)
        )
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(10.dp)
                .clickable { fullScreen = true },
            contentAlignment = Alignment.Center
        ) {
            Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Gym attendance QR code")
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(Icons.Filled.Fullscreen, "Display", GymColors.Accent, Modifier.weight(1f)) { fullScreen = true }
            ActionButton(Icons.Filled.Share, "Share", GymColors.Accent, Modifier.weight(1f)) {
                QrShareUtils.shareBitmap(context, qrBitmap, "gym_attendance_qr.png", "Share gym attendance QR")
            }
        }
    }

    if (fullScreen) {
        Dialog(onDismissRequest = { fullScreen = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Gym attendance QR code",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("MAJOR GYM \u2014 Scan to mark attendance", color = androidx.compose.ui.graphics.Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(16.dp)).background(GymColors.Surface).padding(14.dp)) {
        Text(label.uppercase(), color = GymColors.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(value, color = GymColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Members list ----------

@Composable
fun MembersScreen(members: List<Member>, onNavigate: (Screen) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = members.filter {
        it.name.contains(query, ignoreCase = true) || it.phone.contains(query) || it.idProof.contains(query, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        Text("MEMBERS", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search by name or phone", color = GymColors.TextFaint) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = GymColors.TextFaint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = gymFieldColors()
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
            items(filtered) { m ->
                val status = statusOf(m.expiryMillis)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(GymColors.Surface)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).clickable { onNavigate(Screen.Profile(m.id)) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusRing(m.photoPath, m.name, status, 48.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(m.name, color = GymColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${m.phone} \u00B7 ${m.plan}", color = GymColors.TextMuted, fontSize = 11.sp)
                            Text("Expires ${formatDate(m.expiryMillis)}", color = GymColors.TextFaint, fontSize = 11.sp)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        StatusBadge(status)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Renew",
                            color = GymColors.Accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GymColors.Accent.copy(alpha = 0.15f))
                                .clickable { onNavigate(Screen.Renew(m.id)) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------- Add / Edit member ----------

/** Creates a fresh temp-file content URI for the camera to write a captured photo into. */
private fun newCameraCaptureUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun AddEditMemberScreen(vm: MembersViewModel, existing: Member?, onNavigate: (Screen) -> Unit) {
    val id = remember { existing?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var photoPath by remember { mutableStateOf(existing?.photoPath) }
    var joined by remember { mutableStateOf(existing?.joinedMillis?.toLocalDate() ?: LocalDate.now()) }
    var plan by remember { mutableStateOf(existing?.plan ?: "1 Month") }
    var fee by remember { mutableStateOf(existing?.fee?.toInt()?.toString() ?: "") }

    var passkey by remember { mutableStateOf(existing?.let { "" } ?: PasskeyUtils.generate()) }
    var idProof by remember { mutableStateOf(existing?.idProof ?: "") }
    var idProofError by remember { mutableStateOf(false) }
    var idProofPhotoPath by remember { mutableStateOf(existing?.idProofPhotoPath ?: "") }
    var showIdPhotoSourceSheet by remember { mutableStateOf(false) }
    var confirmDeleteIdPhoto by remember { mutableStateOf(false) }
    var pendingIdCameraUri by remember { mutableStateOf<Uri?>(null) }
    var phoneTaken by remember { mutableStateOf(false) }
    val context = LocalContext.current

    var showPhotoSourceSheet by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { photoPath = vm.savePhoto(id, it) }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCameraUri?.let { photoPath = vm.savePhoto(id, it) }
    }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = newCameraCaptureUri(context)
            pendingCameraUri = uri
            takePhoto.launch(uri)
        }
    }

    val pickIdPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { idProofPhotoPath = vm.saveIdProofPhoto(id, it) }
    }
    val takeIdPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingIdCameraUri?.let { idProofPhotoPath = vm.saveIdProofPhoto(id, it) }
    }
    val requestIdCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = newCameraCaptureUri(context)
            pendingIdCameraUri = uri
            takeIdPhoto.launch(uri)
        }
    }

    // Duplicate-phone check (spec section 1): re-checked whenever the phone field changes.
    LaunchedEffect(phone) {
        phoneTaken = if (phone.length >= 10) vm.isPhoneTaken(phone, excludingId = id) else false
    }

    val months = PLAN_MONTHS[plan] ?: 1L
    val expiryMillis = addMonthsMillis(joined.toMillis(), months)
    val valid = name.trim().length >= 3 && phone.length >= 10 && fee.toDoubleOrNull() != null && !phoneTaken

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 90.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ArrowBack, null, tint = GymColors.Text,
                modifier = Modifier.clickable { onNavigate(existing?.let { Screen.Profile(it.id) } ?: Screen.Members) }
            )
            Spacer(Modifier.width(8.dp))
            Text(if (existing == null) "ADD MEMBER" else "EDIT MEMBER", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .size(92.dp)
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .background(GymColors.Surface2)
                .border(2.dp, GymColors.Border, CircleShape)
                .clickable { showPhotoSourceSheet = true },
            contentAlignment = Alignment.Center
        ) {
            val p = photoPath
            if (p != null && File(p).exists()) {
                AsyncImage(model = File(p), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
            } else {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = GymColors.TextFaint)
            }
        }
        Text(
            "Tap to add photo", color = GymColors.TextFaint, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 20.dp)
        )

        LabeledField("Full Name") {
            OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = gymFieldColors())
        }
        LabeledField("Phone Number") {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { c -> c.isDigit() }.take(10) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = gymFieldColors(),
                isError = phoneTaken
            )
            if (phoneTaken) {
                Text(
                    "This phone number is already registered.",
                    color = GymColors.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        LabeledField("ID Proof (Optional)") {
            OutlinedTextField(
                value = idProof,
                onValueChange = { input ->
                    val filtered = input.filter { it.isLetterOrDigit() && it.code < 128 }
                    idProofError = filtered != input
                    idProof = filtered
                },
                placeholder = { Text("Enter ID Number (Optional)", color = GymColors.TextFaint) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = gymFieldColors(),
                isError = idProofError
            )
            if (idProofError) {
                Text(
                    "Only letters and numbers are allowed.",
                    color = GymColors.Danger, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        LabeledField("ID Proof Photo (Optional)") {
            Text(
                "Capture or upload an image of the member's ID proof for future verification.",
                color = GymColors.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp)
            )
            val idPhotoFile = idProofPhotoPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
            if (idPhotoFile != null) {
                Column {
                    AsyncImage(
                        model = idPhotoFile, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { showIdPhotoSourceSheet = true }, modifier = Modifier.weight(1f)) {
                            Text("Replace", color = GymColors.Text, fontSize = 13.sp)
                        }
                        OutlinedButton(onClick = { confirmDeleteIdPhoto = true }, modifier = Modifier.weight(1f)) {
                            Text("Delete", color = GymColors.Danger, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GymColors.Surface2)
                        .border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))
                        .clickable { showIdPhotoSourceSheet = true }
                        .padding(20.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, null, tint = GymColors.TextFaint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Description, null, tint = GymColors.TextFaint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Tap to add ID Proof Photo", color = GymColors.TextFaint, fontSize = 13.sp)
                }
            }
        }

        if (existing == null) {
            LabeledField("Passkey") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = passkey, onValueChange = {}, readOnly = true,
                        modifier = Modifier.weight(1f), singleLine = true, colors = gymFieldColors()
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Refresh, contentDescription = "Regenerate", tint = GymColors.Accent,
                        modifier = Modifier.clickable { passkey = PasskeyUtils.generate() }
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        Icons.Filled.ContentCopy, contentDescription = "Copy", tint = GymColors.Accent,
                        modifier = Modifier.clickable {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Passkey", passkey))
                            Toast.makeText(context, "Passkey copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                Text(
                    "Shown only once. It will be sent to the member via WhatsApp after saving.",
                    color = GymColors.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        LabeledField("Joining Date") { DatePickerField(joined) { joined = it } }
        LabeledField("Membership Plan") { PlanGrid(plan) { plan = it } }
        LabeledField("Fee (\u20B9)") {
            OutlinedTextField(
                value = fee,
                onValueChange = { fee = it.filter { c -> c.isDigit() } },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = gymFieldColors()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GymColors.Success.copy(alpha = 0.14f)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Auto-calculated expiry", color = GymColors.Success, fontSize = 12.sp)
            Text(formatDate(expiryMillis), color = GymColors.Success, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                val feeVal = fee.toDouble()
                val history = if (existing == null)
                    listOf(HistoryEntry("Joined", plan, feeVal, joined.toMillis(), expiryMillis))
                else existing.historyJson.toHistoryList()
                val member = Member(
                    id = id, name = name.trim(), phone = phone, photoPath = photoPath,
                    plan = plan, fee = feeVal, joinedMillis = joined.toMillis(),
                    expiryMillis = expiryMillis, historyJson = history.toJson(),
                    updatedAtMillis = System.currentTimeMillis(),
                    passwordHash = if (existing == null) PasskeyUtils.hash(passkey) else existing.passwordHash,
                    createdAtMillis = existing?.createdAtMillis ?: System.currentTimeMillis(),
                    lastAttendanceMillis = existing?.lastAttendanceMillis,
                    archived = existing?.archived ?: false,
                    // Add-on: unique, time-limited QR — a brand-new member gets a fresh
                    // token/expiry; a plain edit (not add, not renew) keeps the existing one.
                    qrToken = existing?.qrToken ?: QrUtils.freshToken(),
                    qrTokenExpiryMillis = existing?.qrTokenExpiryMillis
                        ?: (System.currentTimeMillis() + QrUtils.TOKEN_VALIDITY_MILLIS),
                    idProof = idProof,
                    idProofPhotoPath = idProofPhotoPath
                )
                vm.save(member)
                if (existing == null) onNavigate(Screen.Registered(id, passkey)) else onNavigate(Screen.Profile(id))
            },
            enabled = valid,
            colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent, disabledContainerColor = GymColors.Surface2),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (existing == null) "Add Member" else "Save Changes", fontWeight = FontWeight.Bold)
        }
    }

    if (showPhotoSourceSheet) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceSheet = false },
            containerColor = GymColors.Surface,
            title = { Text("Add Photo", color = GymColors.Text) },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showPhotoSourceSheet = false
                                pickPhoto.launch("image/*")
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, null, tint = GymColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text("Choose from Gallery", color = GymColors.Text, fontSize = 14.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showPhotoSourceSheet = false
                                requestCameraPermission.launch(android.Manifest.permission.CAMERA)
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Filled.CameraAlt, null, tint = GymColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text("Take Photo", color = GymColors.Text, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPhotoSourceSheet = false }) { Text("Cancel", color = GymColors.TextMuted) }
            }
        )
    }

    if (showIdPhotoSourceSheet) {
        AlertDialog(
            onDismissRequest = { showIdPhotoSourceSheet = false },
            containerColor = GymColors.Surface,
            title = { Text("ID Proof Photo", color = GymColors.Text) },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showIdPhotoSourceSheet = false
                                requestIdCameraPermission.launch(android.Manifest.permission.CAMERA)
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Filled.CameraAlt, null, tint = GymColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text("Camera", color = GymColors.Text, fontSize = 14.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showIdPhotoSourceSheet = false
                                pickIdPhoto.launch("image/*")
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, null, tint = GymColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text("Gallery", color = GymColors.Text, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIdPhotoSourceSheet = false }) { Text("Cancel", color = GymColors.TextMuted) }
            }
        )
    }

    if (confirmDeleteIdPhoto) {
        AlertDialog(
            onDismissRequest = { confirmDeleteIdPhoto = false },
            containerColor = GymColors.Surface,
            title = { Text("Remove ID Proof Photo?", color = GymColors.Text) },
            text = { Text("This can't be undone.", color = GymColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteIdProofPhoto(id)
                    idProofPhotoPath = ""
                    confirmDeleteIdPhoto = false
                }) { Text("Delete", color = GymColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteIdPhoto = false }) { Text("Cancel", color = GymColors.TextMuted) }
            }
        )
    }
}

// ---------- Profile ----------

@Composable
fun ProfileScreen(member: Member, vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    var showPhoto by remember { mutableStateOf(false) }
    var showIdPhoto by remember { mutableStateOf(false) }
    val status = statusOf(member.expiryMillis)
    val days = daysBetweenNow(member.expiryMillis)
    val history = remember(member.historyJson) { member.historyJson.toHistoryList().reversed() }
    // Most recent renewal date, if any — history is already newest-first, so
    // the first "Renewed" entry (as opposed to the original "Joined" entry)
    // is the latest one. Members who've never renewed simply won't have one.
    val lastRenewedMillis = remember(history) { history.firstOrNull { it.type == "Renewed" }?.dateMillis }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 90.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(Icons.Filled.ArrowBack, null, tint = GymColors.Text, modifier = Modifier.clickable { onNavigate(Screen.Members) })
                Spacer(Modifier.width(8.dp))
                Text("MEMBER PROFILE", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Box(modifier = Modifier.clickable { showPhoto = true }) {
                    StatusRing(member.photoPath, member.name, status, 92.dp)
                }
                Spacer(Modifier.height(10.dp))
                Text(member.name, color = GymColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                StatusBadge(status)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (days < 0) "Expired ${-days} day(s) ago" else "$days day(s) remaining",
                    color = GymColors.TextFaint, fontSize = 11.sp
                )
            }
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(GymColors.Surface).padding(16.dp)) {
                ProfileRow(Icons.Filled.Phone, "Phone", member.phone)
                ProfileRow(Icons.Filled.CalendarToday, "Joined", formatDate(member.joinedMillis))
                if (lastRenewedMillis != null) {
                    ProfileRow(Icons.Filled.Refresh, "Renewed", formatDate(lastRenewedMillis))
                }
                ProfileRow(Icons.Filled.CalendarToday, "Expires", formatDate(member.expiryMillis))
                ProfileRow(Icons.Filled.CurrencyRupee, "Current Plan", "${member.plan} \u00B7 ${formatMoney(member.fee)}")
                ProfileRow(Icons.Filled.Badge, "ID Proof", member.idProof.ifBlank { "Not Provided" })
                ProfileRow(Icons.Filled.Fingerprint, "Fingerprint", if (member.fingerprintTemplate != null) "Enrolled" else "Not Enrolled", last = true)
            }
            Spacer(Modifier.height(14.dp))
            Card(colors = CardDefaults.cardColors(containerColor = GymColors.Surface), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("ID PROOF PHOTO", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    val idPhotoFile = member.idProofPhotoPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
                    if (idPhotoFile != null) {
                        AsyncImage(
                            model = idPhotoFile, contentDescription = "ID proof photo", contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showIdPhoto = true }
                        )
                    } else {
                        Text("No ID Proof Photo", color = GymColors.TextFaint, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                ActionButton(Icons.Filled.Refresh, "Renew", GymColors.Accent, Modifier.weight(1f)) { onNavigate(Screen.Renew(member.id)) }
                ActionButton(Icons.Filled.Edit, "Edit", GymColors.TextMuted, Modifier.weight(1f)) { onNavigate(Screen.Edit(member.id)) }
                ActionButton(Icons.Filled.Delete, "Delete", GymColors.Danger, Modifier.weight(1f)) { confirmDelete = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                // Add-on: view the member's current QR, or force a fresh time-limited
                // token/expiry on demand (e.g. after the current one has lapsed).
                ActionButton(Icons.Filled.QrCode, if (QrUtils.isTokenValid(member)) "View QR" else "Regenerate QR", GymColors.Accent, Modifier.weight(1f)) {
                    if (!QrUtils.isTokenValid(member)) {
                        vm.save(
                            member.copy(
                                qrToken = QrUtils.freshToken(),
                                qrTokenExpiryMillis = System.currentTimeMillis() + QrUtils.TOKEN_VALIDITY_MILLIS,
                                updatedAtMillis = System.currentTimeMillis()
                            )
                        )
                    }
                    onNavigate(Screen.Renewed(member.id))
                }
                ActionButton(
                    Icons.Filled.Fingerprint,
                    if (member.fingerprintTemplate != null) "Re-enroll" else "Enroll",
                    GymColors.Accent,
                    Modifier.weight(1f)
                ) { onNavigate(Screen.EnrollFingerprint(member.id)) }
            }
            Text("HISTORY", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(history) { h ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(GymColors.Surface).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("${h.type} \u00B7 ${h.plan}", color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text("${formatDate(h.dateMillis)} \u2192 ${formatDate(h.expiryMillis)}", color = GymColors.TextMuted, fontSize = 11.sp)
                }
                Text(formatMoney(h.fee), color = GymColors.Gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showPhoto) {
        FullScreenPhotoViewer(member.photoPath, member.name, onDismiss = { showPhoto = false })
    }
    if (showIdPhoto) {
        FullScreenPhotoViewer(member.idProofPhotoPath.ifBlank { null }, "${member.name} \u2014 ID Proof", onDismiss = { showIdPhoto = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = GymColors.Surface,
            title = { Text("Delete member", color = GymColors.Text) },
            text = { Text("Delete ${member.name}? This cannot be undone.", color = GymColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = { vm.delete(member); onNavigate(Screen.Members) }) { Text("Delete", color = GymColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = GymColors.TextMuted) }
            }
        )
    }
}

@Composable
fun ProfileRow(icon: ImageVector, label: String, value: String, last: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = GymColors.TextFaint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = GymColors.TextFaint, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
    if (!last) Divider(color = GymColors.Border, thickness = 1.dp)
}

@Composable
fun ActionButton(icon: ImageVector, label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(GymColors.Surface).clickable { onClick() }.padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = color, fontSize = 11.sp)
    }
}

// ---------- Renew ----------

@Composable
fun RenewScreen(member: Member, vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    var plan by remember { mutableStateOf(member.plan) }
    var fee by remember { mutableStateOf(member.fee.toInt().toString()) }
    val today = LocalDate.now().toMillis()
    val base = if (member.expiryMillis > today) member.expiryMillis else today
    val months = PLAN_MONTHS[plan] ?: 1L
    val newExpiry = addMonthsMillis(base, months)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 90.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Icon(Icons.Filled.ArrowBack, null, tint = GymColors.Text, modifier = Modifier.clickable { onNavigate(Screen.Profile(member.id)) })
            Spacer(Modifier.width(8.dp))
            Text("RENEW MEMBERSHIP", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
            StatusRing(member.photoPath, member.name, statusOf(member.expiryMillis), 52.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(member.name, color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text("Current expiry: ${formatDate(member.expiryMillis)}", color = GymColors.TextMuted, fontSize = 11.sp)
            }
        }
        LabeledField("Renewal Plan") { PlanGrid(plan) { plan = it } }
        LabeledField("Fee (\u20B9)") {
            OutlinedTextField(
                value = fee,
                onValueChange = { fee = it.filter { c -> c.isDigit() } },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = gymFieldColors()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GymColors.Success.copy(alpha = 0.14f)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("New expiry date", color = GymColors.Success, fontSize = 12.sp)
            Text(formatDate(newExpiry), color = GymColors.Success, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val feeVal = fee.toDoubleOrNull() ?: 0.0
                val newHistory = member.historyJson.toHistoryList() + HistoryEntry("Renewed", plan, feeVal, System.currentTimeMillis(), newExpiry)
                vm.save(
                    member.copy(
                        plan = plan, fee = feeVal, expiryMillis = newExpiry, historyJson = newHistory.toJson(),
                        updatedAtMillis = System.currentTimeMillis(),
                        // Add-on: every renewal rotates the QR token to a brand-new one with a
                        // fresh expiry window, so a QR captured before this renewal can never be
                        // replayed by a client to read the member's old membership data.
                        qrToken = QrUtils.freshToken(),
                        qrTokenExpiryMillis = System.currentTimeMillis() + QrUtils.TOKEN_VALIDITY_MILLIS
                    )
                )
                onNavigate(Screen.Renewed(member.id, justRenewed = true))
            },
            colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Confirm Renewal", fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Renewal / QR success ----------

/**
 * Shown after a renewal is confirmed, and also reachable on-demand from the
 * Profile screen's QR button (add-on: unique, time-limited membership QR).
 * Always displays the member's *current* qrToken, so it's correct whether it
 * was just rotated by a renewal or by a manual regenerate.
 */
@Composable
fun RenewalSuccessScreen(member: Member, justRenewed: Boolean = false, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val qrBitmap = remember(member.qrToken) { QrUtils.memberQrBitmap(member) }
    val valid = QrUtils.isTokenValid(member)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GymColors.Success, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(if (justRenewed) "MEMBERSHIP RENEWED" else "QR UPDATED", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(member.name, color = GymColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Member QR code")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (valid) "Valid until ${formatDateTime(member.qrTokenExpiryMillis)}" else "Expired \u2014 regenerate before sharing",
            color = if (valid) GymColors.TextFaint else GymColors.Danger,
            fontSize = 11.sp
        )
        Text(
            "This QR replaces any earlier one \u2014 old QRs no longer work.",
            color = GymColors.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(28.dp))

        if (justRenewed) {
            Button(
                onClick = { WhatsAppShare.shareRenewal(context, member) },
                colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share Renewal Update", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onNavigate(Screen.Profile(member.id)) },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Done")
            }
        } else {
            Button(
                onClick = { onNavigate(Screen.Profile(member.id)) },
                colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------- Backup ----------

@Composable
fun BackupScreen(vm: MembersViewModel) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var shareMessage by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var latestBackup by remember { mutableStateOf(vm.latestBackupFile()) }

    fun refreshLatestBackup() { latestBackup = vm.latestBackupFile() }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            vm.exportJson { json ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                message = "Backup exported."
                refreshLatestBackup()
            }
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (json != null) {
                vm.importJson(json)
                message = "Records restored."
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).padding(top = 20.dp, bottom = 90.dp)) {
        Text("BACKUP", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        Text("Export your gym records, or restore them on a new phone.", color = GymColors.TextMuted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        Card(colors = CardDefaults.cardColors(containerColor = GymColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Download, null, tint = GymColors.Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export All Records", color = GymColors.Text, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text("Saves every member, photo, plan and payment history to a file.", color = GymColors.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { createDoc.launch("major-gym-backup.json") },
                    colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Export Backup") }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(colors = CardDefaults.cardColors(containerColor = GymColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Upload, null, tint = GymColors.Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore Records", color = GymColors.Text, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text("Reinstalled the app or switched phones? Load your last backup file.", color = GymColors.TextMuted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { openDoc.launch(arrayOf("application/json")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose Backup File", color = GymColors.Text)
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = GymColors.Success, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Spacer(Modifier.height(14.dp))
        Card(colors = CardDefaults.cardColors(containerColor = GymColors.Surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Share, null, tint = GymColors.Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share Backup File", color = GymColors.Text, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Quickly share your latest MajorGym backup with WhatsApp, Google Drive, Gmail, Telegram, " +
                        "Bluetooth or any compatible application installed on your phone.",
                    color = GymColors.TextMuted, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))

                if (latestBackup != null) {
                    val f = latestBackup!!
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(GymColors.Surface2).padding(10.dp)) {
                        Text(f.name, color = GymColors.Text, fontSize = 11.sp)
                        Text(
                            "${formatBackupSize(f.length())} \u00B7 ${formatDate(f.lastModified())} \u00B7 ${formatTimeOfDay(f.lastModified())}",
                            color = GymColors.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Button(
                    onClick = {
                        sharing = true
                        shareMessage = if (latestBackup == null) "No backup found. Creating a backup\u2026" else null
                        vm.getOrCreateLatestBackup { file ->
                            sharing = false
                            if (file != null) {
                                shareMessage = "Backup ready to share."
                                refreshLatestBackup()
                                BackupShareUtils.shareBackupFile(context, file)
                            } else {
                                shareMessage = "Unable to create backup."
                            }
                        }
                    },
                    enabled = !sharing,
                    colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent, disabledContainerColor = GymColors.Surface2),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (sharing) "Preparing\u2026" else "Share Backup") }

                shareMessage?.let {
                    Text(it, color = GymColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
