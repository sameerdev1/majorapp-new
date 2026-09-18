package com.majorgym.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majorgym.app.MembersViewModel
import com.majorgym.app.Screen
import com.majorgym.app.data.*
import java.time.LocalDate

private enum class AttendanceFilter(val label: String) {
    ALL("All"),
    MORNING("Morning"),
    EVENING("Evening"),
    ACTIVE("Active Members"),
    EXPIRED("Expired Members")
}

/** Opens the same platform date picker [DatePickerField] uses, without
 *  needing that composable's own boxed visual (this screen has two separate
 *  date-picker entry points - the top-right calendar button and the
 *  "Select Date" control - and both should open the exact same picker on the
 *  exact same [selected] state, never two different implementations). */
@Composable
private fun rememberDatePickerLauncher(selected: LocalDate, onChange: (LocalDate) -> Unit): () -> Unit {
    val context = LocalContext.current
    return {
        android.app.DatePickerDialog(
            context,
            { _, y, m, d -> onChange(LocalDate.of(y, m + 1, d)) },
            selected.year, selected.monthValue - 1, selected.dayOfMonth
        ).show()
    }
}

/**
 * Attendance Logs (replaces "Members" in the bottom nav - spec section 9).
 * Reads only from the existing member list plus the new, additive
 * [AttendanceRecord] log (see that file for why the log was necessary).
 * Does not touch, duplicate, or recompute anything the app already tracks:
 * membership status still comes from [statusOf], photos still come from
 * [Member.photoPath] via [StatusRing], search matches the same
 * name/phone/idProof fields used everywhere else in the app.
 */
@Composable
fun AttendanceLogsScreen(members: List<Member>, vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AttendanceFilter.ALL) }
    var showFilterMenu by remember { mutableStateOf(false) }
    val openDatePicker = rememberDatePickerLauncher(selectedDate) { selectedDate = it }

    // Attendance Count Visibility Settings - independent show/hide for the
    // Present/Morning/Evening numbers only (see AttendanceSettingsPrefs).
    // Purely a display preference: never touches attendance data/calculations,
    // and never touches the separate Dashboard privacy settings.
    val context = LocalContext.current
    val attendancePrefs = remember { AttendanceSettingsPrefs(context) }
    var showCountSettings by remember { mutableStateOf(false) }
    var presentVisible by remember { mutableStateOf(attendancePrefs.isCountVisible(AttendanceCount.PRESENT)) }
    var morningVisible by remember { mutableStateOf(attendancePrefs.isCountVisible(AttendanceCount.MORNING)) }
    var eveningVisible by remember { mutableStateOf(attendancePrefs.isCountVisible(AttendanceCount.EVENING)) }

    // Scoped to just this one day - see AttendanceDao.observeForDay - so
    // switching dates never pulls the whole historical log into memory.
    val dayRecords by produceState(initialValue = emptyList<AttendanceRecord>(), selectedDate) {
        vm.attendanceForDay(selectedDate).collect { value = it }
    }
    val membersById = remember(members) { members.associateBy { it.id } }

    // One row per member per day: their earliest scan of that day is what
    // "PRESENT" / the displayed check-in time refers to.
    val dayEntries = remember(dayRecords, membersById) {
        dayRecords.groupBy { it.memberId }
            .mapNotNull { (memberId, recs) ->
                val earliest = recs.minByOrNull { it.timestampMillis } ?: return@mapNotNull null
                val member = membersById[memberId] ?: return@mapNotNull null
                earliest to member
            }
    }

    val searched = if (query.isBlank()) dayEntries else dayEntries.filter { (_, m) ->
        m.name.contains(query, ignoreCase = true) || m.phone.contains(query) || m.idProof.contains(query, ignoreCase = true)
    }

    val filtered = when (filter) {
        AttendanceFilter.ALL -> searched
        AttendanceFilter.MORNING -> searched.filter { it.first.session == AttendanceSession.MORNING.name }
        AttendanceFilter.EVENING -> searched.filter { it.first.session == AttendanceSession.EVENING.name }
        AttendanceFilter.ACTIVE -> searched.filter { statusOf(it.second.expiryMillis) == MemberStatus.ACTIVE }
        AttendanceFilter.EXPIRED -> searched.filter { statusOf(it.second.expiryMillis) == MemberStatus.EXPIRED }
    }.sortedByDescending { it.first.timestampMillis }

    val presentCount = filtered.size
    val morningCount = filtered.count { it.first.session == AttendanceSession.MORNING.name }
    val eveningCount = filtered.count { it.first.session == AttendanceSession.EVENING.name }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 90.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GymScreenTitle("ATTENDANCE LOGS")
                    val todaySuffix = if (selectedDate == LocalDate.now()) " (Today)" else ""
                    Text(
                        "\uD83D\uDCC5 ${formatDate(selectedDate.toMillis())}$todaySuffix",
                        color = GymColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                IconButton(onClick = { showCountSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Attendance count settings", tint = GymColors.Accent)
                }
                IconButton(onClick = openDatePicker) {
                    Icon(Icons.Filled.CalendarToday, contentDescription = "Pick date", tint = GymColors.Accent)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                // A hidden count passes null to StatCard, which keeps the card
                // and its label visible and only omits the number - see
                // StatCard's existing null-value behavior. Attendance data and
                // calculations above (presentCount/morningCount/eveningCount)
                // are computed exactly as before either way.
                StatCard("Present", if (presentVisible) presentCount.toString() else null, Modifier.weight(1f))
                StatCard("Morning", if (morningVisible) morningCount.toString() else null, Modifier.weight(1f))
                StatCard("Evening", if (eveningVisible) eveningCount.toString() else null, Modifier.weight(1f))
            }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search by name, phone or ID proof", color = GymColors.TextFaint) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = GymColors.Accent) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = gymFieldColors()
            )
            Spacer(Modifier.height(12.dp))
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                // Select Date (spec section 4) - same picker/state as the
                // top-right calendar button above, never a second one.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GymColors.SurfaceCard)
                        .border(1.dp, GymColors.Border, RoundedCornerShape(12.dp))
                        .clickable(onClick = openDatePicker)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.CalendarToday, contentDescription = null, tint = GymColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Select Date", color = GymColors.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Text(formatDate(selectedDate.toMillis()), color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Filter (spec section 5) - only changes what's displayed;
                // never touches the underlying attendance data.
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(GymColors.SurfaceCard)
                            .border(1.dp, GymColors.Border, RoundedCornerShape(12.dp))
                            .clickable { showFilterMenu = true }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.FilterList, contentDescription = null, tint = GymColors.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Filter", color = GymColors.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            Text(filter.label, color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                        AttendanceFilter.entries.forEach { f ->
                            DropdownMenuItem(text = { Text(f.label) }, onClick = { filter = f; showFilterMenu = false })
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                val message = when {
                    query.isNotBlank() -> "No attendance records match your search."
                    dayEntries.isEmpty() -> "No attendance recorded for this date."
                    else -> "No attendance records match this filter."
                }
                Text(message, color = GymColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            items(filtered, key = { it.first.id }) { (record, member) ->
                AttendanceRecordCard(record, member) { onNavigate(Screen.AttendanceHistory(member.id)) }
            }
        }
    }

    // Attendance Count Visibility Settings dialog - same shape/pattern as the
    // existing Dashboard Number Visibility dialog (see DashboardVisibilityRow
    // in Screens.kt), just backed by AttendanceSettingsPrefs and scoped to
    // only these three counts.
    if (showCountSettings) {
        AlertDialog(
            onDismissRequest = { showCountSettings = false },
            containerColor = GymColors.SurfaceCard,
            title = { Text("Attendance Count Settings", color = GymColors.Text, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Each count can be shown or hidden independently. The card and its label always stay visible.",
                        color = GymColors.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)
                    )
                    DashboardVisibilityRow("Present Count", presentVisible) {
                        presentVisible = it; attendancePrefs.setCountVisible(AttendanceCount.PRESENT, it)
                    }
                    DashboardVisibilityRow("Morning Count", morningVisible) {
                        morningVisible = it; attendancePrefs.setCountVisible(AttendanceCount.MORNING, it)
                    }
                    DashboardVisibilityRow("Evening Count", eveningVisible) {
                        eveningVisible = it; attendancePrefs.setCountVisible(AttendanceCount.EVENING, it)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountSettings = false }) { Text("Done", color = GymColors.Accent) }
            }
        )
    }
}

/**
 * Section 6: one attendance record. Reuses [StatusRing] (real member photo
 * or the app's existing default-avatar behavior) and [statusOf] (the same
 * membership-status calculation used everywhere else) rather than any new
 * logic of its own.
 */
@Composable
private fun AttendanceRecordCard(record: AttendanceRecord, member: Member, onClick: () -> Unit) {
    val status = statusOf(member.expiryMillis)
    val session = if (record.session == AttendanceSession.MORNING.name) "MORNING" else "EVENING"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(GymShapes.lg)
            .background(GymColors.CardGlassGradient)
            .border(1.dp, GymColors.Border, GymShapes.lg)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusRing(member.photoPath, member.name, status, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(member.name, color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(member.phone, color = GymColors.TextMuted, fontSize = 12.sp)
            Text(
                "${statusLabel(status)} \u2022 ${member.plan}",
                color = GymColors.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = GymColors.Success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("PRESENT", color = GymColors.Success, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(formatTimeOfDay(record.timestampMillis), color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(session, color = GymColors.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        }
    }
}

private fun statusLabel(status: MemberStatus): String = when (status) {
    MemberStatus.ACTIVE -> "ACTIVE"
    MemberStatus.EXPIRING -> "EXPIRING SOON"
    MemberStatus.EXPIRED -> "EXPIRED"
}

/**
 * Change 3: tap-through attendance history for one member, showing every
 * real check-in still within the 4-month retention window, grouped by month
 * (newest month first, newest visit first within a month) - e.g.:
 *
 *   August 2026
 *   28 Aug — 08:42 AM — Morning
 *   27 Aug — 08:51 AM — Morning
 *
 *   July 2026
 *   31 Jul — 08:40 AM — Morning
 *
 * Reads only [AttendanceRecord] rows the app already recorded - never
 * generates a record, and never writes, edits, or deletes one. Whatever
 * remains after the daily retention cleanup (Change 1) is exactly what shows
 * up here; there's no separate lookback window of its own.
 */
@Composable
fun AttendanceHistoryScreen(memberId: String, members: List<Member>, vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    val member = members.find { it.id == memberId }
    val records by produceState(initialValue = emptyList<AttendanceRecord>(), memberId) {
        vm.attendanceHistoryForMember(memberId).collect { value = it }
    }
    // Grouped by calendar month, newest first; within a month, newest visit
    // first - matches the spec's example layout exactly.
    val byMonth = remember(records) {
        records
            .sortedByDescending { it.timestampMillis }
            .groupBy { it.timestampMillis.toLocalDate().let { d -> java.time.YearMonth.from(d) } }
    }
    // Section 4: attendance percentage window is membership start date ->
    // today, recomputed on every recomposition (no caching of "today"), so it
    // keeps itself correct as the date changes without any extra wiring.
    // attendedDays counts distinct calendar days with a visit, never raw scan
    // count, since one member can check in more than once in a day.
    val attendedDays = remember(records) { records.map { it.dayEpoch }.distinct().size }
    val eligibleDays = remember(member?.joinedMillis) { member?.let { eligibleAttendanceDays(it.joinedMillis) } ?: 0 }
    val attendancePercent = remember(attendedDays, eligibleDays) { attendancePercentage(attendedDays, eligibleDays) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = GymColors.Text,
                modifier = Modifier.clickable { onNavigate(Screen.AttendanceLogs) }
            )
            Spacer(Modifier.width(12.dp))
            Text("MEMBER PROFILE", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp, fontFamily = GymFonts.Display)
        }
        if (member != null) {
            MemberAttendanceProfileCard(member, attendancePercent, attendedDays, eligibleDays)
        }
        GymSectionLabel("Attendance History", modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        if (byMonth.isEmpty()) {
            Text("No attendance recorded yet.", color = GymColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
            byMonth.forEach { (month, monthRecords) ->
                item(key = "header_$month") {
                    Text(
                        month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + month.year,
                        color = GymColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                    )
                }
                items(monthRecords, key = { it.id }) { rec ->
                    val sessionLabel = if (rec.session == AttendanceSession.MORNING.name) "Morning" else "Evening"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(GymShapes.md)
                            .background(GymColors.SurfaceCard)
                            .border(1.dp, GymColors.Border, GymShapes.md)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(formatDate(rec.timestampMillis), color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = GymColors.Success, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(formatTimeOfDay(rec.timestampMillis), color = GymColors.Success, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Text(sessionLabel, color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Section 3: dedicated member attendance profile shown above the existing
 * month-grouped attendance history, styled after the provided reference
 * image (photo, name, phone, live status, a percentage ring, membership
 * plan/start/expiry, and attended-vs-eligible days). Reads only fields
 * [Member] and the attendance percentage calculation already expose - it
 * doesn't add, recompute, or duplicate any membership/attendance data of its
 * own, and doesn't touch any other screen's layout.
 */
@Composable
private fun MemberAttendanceProfileCard(member: Member, attendancePercent: Int, attendedDays: Int, eligibleDays: Int) {
    val status = statusOf(member.expiryMillis)
    val days = daysBetweenNow(member.expiryMillis)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(GymShapes.lg)
            .background(GymColors.CardGlassGradient)
            .border(1.dp, GymColors.Border, GymShapes.lg)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusRing(member.photoPath, member.name, status, 64.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(member.name, color = GymColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(member.phone, color = GymColors.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(6.dp))
                StatusBadge(status)
            }
            Spacer(Modifier.width(10.dp))
            AttendancePercentRing(attendancePercent, daysLeftLabel = if (days < 0) "Expired" else "$days Days Left")
        }
        Spacer(Modifier.height(16.dp))
        GymSectionLabel("Membership Details", modifier = Modifier.padding(bottom = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GymShapes.md)
                .background(GymColors.SurfaceCard)
                .border(1.dp, GymColors.Border, GymShapes.md)
                .padding(horizontal = 14.dp)
        ) {
            ProfileRow(Icons.Filled.CurrencyRupee, "Plan", member.plan)
            ProfileRow(Icons.Filled.CalendarToday, "Start Date", formatDate(member.joinedMillis))
            ProfileRow(Icons.Filled.CalendarToday, "Expiry Date", formatDate(member.expiryMillis), last = true)
        }
        Spacer(Modifier.height(14.dp))
        GymSectionLabel("Attendance Progress", modifier = Modifier.padding(bottom = 8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GymShapes.md)
                .background(GymColors.SurfaceCard)
                .border(1.dp, GymColors.Border, GymShapes.md)
                .padding(14.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Attended Days", color = GymColors.TextMuted, fontSize = 12.sp)
                Text("$attendedDays / $eligibleDays Days", color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            val progress = if (eligibleDays > 0) (attendedDays.toFloat() / eligibleDays.toFloat()).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(GymShapes.pill)
                    .background(GymColors.Surface3)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(GymShapes.pill)
                        .background(GymColors.PrimaryGradient)
                )
            }
        }
    }
}

/** Circular attendance-percentage ring matching the reference image's style -
 *  a cyan-to-violet gradient sweep proportional to [percent], with the
 *  percentage and a short days-left label centered inside. Purely
 *  presentational: takes an already-computed percent, never calculates one
 *  itself. */
@Composable
private fun AttendancePercentRing(percent: Int, daysLeftLabel: String, ringSize: Dp = 84.dp) {
    Box(modifier = Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = ringSize.toPx() * 0.11f
            val inset = strokeWidth / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
            drawArc(
                color = GymColors.Surface3,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(GymColors.AccentBright, GymColors.Violet, GymColors.AccentBright)),
                startAngle = -90f,
                sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", color = GymColors.Text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, fontFamily = GymFonts.Display)
            Text(daysLeftLabel, color = GymColors.TextFaint, fontSize = 8.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}
