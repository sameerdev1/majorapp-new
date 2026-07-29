package com.majorgym.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) GymColors.Accent else GymColors.Surface2)
                            .border(1.dp, if (active) GymColors.Accent else GymColors.Border, RoundedCornerShape(10.dp))
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
            .clip(RoundedCornerShape(10.dp))
            .background(GymColors.Surface2)
            .border(1.dp, GymColors.Border, RoundedCornerShape(10.dp))
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
    val attention = members
        .filter { statusOf(it.expiryMillis) != MemberStatus.EXPIRED && daysBetweenNow(it.expiryMillis) <= 7 }
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
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GymColors.Surface).padding(16.dp)
            ) {
                Text("TOTAL REVENUE", color = GymColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(formatMoney(revenue), color = GymColors.Gold, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
        }
        if (attention.isNotEmpty()) {
            item {
                Text("NEEDS ATTENTION", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(attention.take(5)) { m ->
                val status = statusOf(m.expiryMillis)
                val days = daysBetweenNow(m.expiryMillis)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
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
    val filtered = members.filter { it.name.contains(query, ignoreCase = true) || it.phone.contains(query) }

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
                        .clip(RoundedCornerShape(14.dp))
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

@Composable
fun AddEditMemberScreen(vm: MembersViewModel, existing: Member?, onNavigate: (Screen) -> Unit) {
    val id = remember { existing?.id ?: UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var photoPath by remember { mutableStateOf(existing?.photoPath) }
    var joined by remember { mutableStateOf(existing?.joinedMillis?.toLocalDate() ?: LocalDate.now()) }
    var plan by remember { mutableStateOf(existing?.plan ?: "1 Month") }
    var fee by remember { mutableStateOf(existing?.fee?.toInt()?.toString() ?: "") }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { photoPath = vm.savePhoto(id, it) }
    }

    val months = PLAN_MONTHS[plan] ?: 1L
    val expiryMillis = addMonthsMillis(joined.toMillis(), months)
    val valid = name.isNotBlank() && phone.length >= 10 && fee.toDoubleOrNull() != null

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
                .clickable { pickPhoto.launch("image/*") },
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
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = gymFieldColors()
            )
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
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GymColors.Success.copy(alpha = 0.14f)).padding(12.dp),
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
                    id = id, name = name, phone = phone, photoPath = photoPath,
                    plan = plan, fee = feeVal, joinedMillis = joined.toMillis(),
                    expiryMillis = expiryMillis, historyJson = history.toJson(),
                    updatedAtMillis = System.currentTimeMillis()
                )
                vm.save(member)
                onNavigate(Screen.Profile(id))
            },
            enabled = valid,
            colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent, disabledContainerColor = GymColors.Surface2),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (existing == null) "Add Member" else "Save Changes", fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Profile ----------

@Composable
fun ProfileScreen(member: Member, vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val status = statusOf(member.expiryMillis)
    val days = daysBetweenNow(member.expiryMillis)
    val history = remember(member.historyJson) { member.historyJson.toHistoryList().reversed() }

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
                StatusRing(member.photoPath, member.name, status, 92.dp)
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
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(GymColors.Surface).padding(16.dp)) {
                ProfileRow(Icons.Filled.Phone, "Phone", member.phone)
                ProfileRow(Icons.Filled.CalendarToday, "Joined", formatDate(member.joinedMillis))
                ProfileRow(Icons.Filled.CalendarToday, "Expires", formatDate(member.expiryMillis))
                ProfileRow(Icons.Filled.CurrencyRupee, "Current Plan", "${member.plan} \u00B7 ${formatMoney(member.fee)}", last = true)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                ActionButton(Icons.Filled.Refresh, "Renew", GymColors.Accent, Modifier.weight(1f)) { onNavigate(Screen.Renew(member.id)) }
                ActionButton(Icons.Filled.Edit, "Edit", GymColors.TextMuted, Modifier.weight(1f)) { onNavigate(Screen.Edit(member.id)) }
                ActionButton(Icons.Filled.Delete, "Delete", GymColors.Danger, Modifier.weight(1f)) { confirmDelete = true }
            }
            Text("HISTORY", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(history) { h ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)).background(GymColors.Surface).padding(12.dp),
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
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(GymColors.Surface).clickable { onClick() }.padding(vertical = 14.dp),
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
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(GymColors.Success.copy(alpha = 0.14f)).padding(12.dp),
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
                vm.save(member.copy(plan = plan, fee = feeVal, expiryMillis = newExpiry, historyJson = newHistory.toJson(), updatedAtMillis = System.currentTimeMillis()))
                onNavigate(Screen.Profile(member.id))
            },
            colors = ButtonDefaults.buttonColors(containerColor = GymColors.Accent),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Confirm Renewal", fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Backup ----------

@Composable
fun BackupScreen(vm: MembersViewModel) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            vm.exportJson { json ->
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                message = "Backup exported."
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
    }
}
