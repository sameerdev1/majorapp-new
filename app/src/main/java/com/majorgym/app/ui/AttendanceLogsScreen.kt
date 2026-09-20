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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import coil.compose.AsyncImage
import java.io.File
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
 * Member Attendance Details (Attendance -> pick a member).
 *
 * Shows that one member's real check-ins still within the 4-month retention
 * window, grouped by month (newest month first, newest visit first within a
 * month). Reads only [AttendanceRecord] rows the app already recorded - never
 * generates, writes, edits, or deletes one. All numbers (attended days,
 * eligible days, percentage, days left, status) come from exactly the same
 * calculations as before; this composable only changes how they are drawn.
 *
 * Scrolling: member info -> membership -> attendance progress -> attendance
 * history are all items of ONE [LazyColumn] (one scroll container, no nested
 * scrolling). The bottom dock is drawn separately by MainActivity and this
 * list reserves space so nothing sits underneath it.
 *
 * Scroll stability: every item has a stable key and the list state is
 * hoisted, so when a fingerprint check-in adds a row the list keeps the item
 * the user was looking at anchored in place instead of moving the viewport.
 */
@Composable
fun AttendanceHistoryScreen(memberId: String, members: List<Member>, vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    val member = members.find { it.id == memberId }
    val records by produceState(initialValue = emptyList<AttendanceRecord>(), memberId) {
        vm.attendanceHistoryForMember(memberId).collect { value = it }
    }
    // Grouped by calendar month, newest first; within a month, newest visit
    // first.
    val byMonth = remember(records) {
        records
            .sortedByDescending { it.timestampMillis }
            .groupBy { it.timestampMillis.toLocalDate().let { d -> java.time.YearMonth.from(d) } }
    }
    // Attendance percentage window is membership start date -> today,
    // recomputed on every recomposition (no caching of "today"). attendedDays
    // counts distinct calendar days with a visit, never raw scan count, since
    // one member can check in more than once in a day.
    val attendedDays = remember(records) { records.map { it.dayEpoch }.distinct().size }
    val eligibleDays = remember(member?.joinedMillis) { member?.let { eligibleAttendanceDays(it.joinedMillis) } ?: 0 }
    val attendancePercent = remember(attendedDays, eligibleDays) { attendancePercentage(attendedDays, eligibleDays) }

    val listState = rememberLazyListState()
    val systemNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        AttendanceBackdrop()
        Column(modifier = Modifier.fillMaxSize()) {
            AttendanceDetailsHeader(onBack = { onNavigate(Screen.AttendanceLogs) })
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 12.dp,
                    bottom = BottomNavTotalHeight + systemNavInset + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (member != null) {
                    item(key = "ad_hero") {
                        val status = statusOf(member.expiryMillis)
                        val days = daysBetweenNow(member.expiryMillis)
                        AdHeroCard(
                            member = member,
                            status = status,
                            percent = attendancePercent,
                            daysLeftLabel = if (days < 0) "Expired" else "$days Days Left"
                        )
                    }
                    item(key = "ad_membership") { AdMembershipCard(member) }
                    item(key = "ad_progress") { AdProgressCard(attendedDays, eligibleDays) }
                }
                item(key = "ad_history_header") { AdHistoryHeader() }
                if (byMonth.isEmpty()) {
                    item(key = "ad_history_empty") {
                        Text("No attendance recorded yet.", color = GymColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                byMonth.forEach { (month, monthRecords) ->
                    item(key = "ad_month_$month") {
                        AdMonthHeader(
                            month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + month.year
                        )
                    }
                    items(monthRecords, key = { "ad_rec_${it.id}" }) { rec -> AdHistoryRow(rec) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Member Attendance Details - visual system (presentation only)
// ---------------------------------------------------------------------------

private val AdNeonBlue = Color(0xFF2F8BFF)
private val AdNeonCyan = Color(0xFF29D3FF)
private val AdNeonViolet = Color(0xFF7A5CFF)
private val AdLabelBlue = Color(0xFF6FA8FF)
private val AdGold = Color(0xFFFFB020)
private val AdTeal = Color(0xFF34F5C5)
private val AdSoftBorder = Color(0x552F8BFF)
private val AdPanelBrush = Brush.verticalGradient(listOf(Color(0xEB0C1A3D), Color(0xEB060E27)))
private val AdBorderBrush = Brush.linearGradient(
    listOf(AdNeonBlue.copy(alpha = 0.9f), AdNeonViolet.copy(alpha = 0.45f), AdNeonCyan.copy(alpha = 0.75f))
)

/** Dark glass panel: navy gradient, gradient hairline border, soft blue glow. */
private fun Modifier.adPanel(shape: Shape, glow: Boolean = true): Modifier =
    this.let { if (glow) it.neonGlow(AdNeonBlue, alpha = 0.20f, radius = 12.dp, shape = shape) else it }
        .clip(shape)
        .background(AdPanelBrush)
        .border(1.dp, AdBorderBrush, shape)

/** Hero card outline: rounded rectangle whose top edge steps down on the
 *  right (with a slanted transition), like the reference. */
private class AdHeroShape(private val corner: Dp, private val step: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { corner.toPx() }
        val s = with(density) { step.toPx() }
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(w * 0.50f, 0f)
            lineTo(w * 0.58f, s)
            lineTo(w - r, s)
            arcTo(Rect(w - 2 * r, s, w, s + 2 * r), 270f, 90f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(r, h)
            arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
            lineTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

/** Outline dumbbell (bar + stepped plates each side) used as a faint gym
 *  graphic in the screen backdrop. */
private fun DrawScope.drawDumbbell(center: Offset, length: Float, angle: Float, alpha: Float) {
    rotate(degrees = angle, pivot = center) {
        val barH = length * 0.05f
        drawRoundRect(
            color = AdNeonBlue.copy(alpha = alpha * 0.55f),
            topLeft = Offset(center.x - length * 0.34f, center.y - barH / 2f),
            size = Size(length * 0.68f, barH),
            cornerRadius = CornerRadius(barH / 2f)
        )
        // (distance from center, plate thickness, plate height) as fractions of length
        val plates = listOf(
            Triple(0.30f, 0.075f, 0.44f),
            Triple(0.385f, 0.06f, 0.33f),
            Triple(0.45f, 0.05f, 0.21f)
        )
        listOf(-1f, 1f).forEach { side ->
            plates.forEach { (dist, thick, tall) ->
                val pw = length * thick
                val ph = length * tall
                val topLeft = Offset(center.x + side * length * dist - pw / 2f, center.y - ph / 2f)
                val corner = CornerRadius(pw * 0.35f)
                drawRoundRect(
                    color = AdNeonBlue.copy(alpha = alpha * 0.22f),
                    topLeft = topLeft, size = Size(pw, ph), cornerRadius = corner
                )
                drawRoundRect(
                    color = AdNeonCyan.copy(alpha = alpha * 0.55f),
                    topLeft = topLeft, size = Size(pw, ph), cornerRadius = corner,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}

/** Premium dark backdrop: deep-navy gradient, blue/violet atmosphere, diagonal
 *  light streaks and faint dumbbell graphics. Decoration only. */
@Composable
private fun AttendanceBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF050B1F), Color(0xFF040815), Color(0xFF050A1C))))
        val glowA = Offset(w * 0.92f, h * 0.03f)
        drawCircle(
            brush = Brush.radialGradient(listOf(AdNeonBlue.copy(alpha = 0.32f), Color.Transparent), center = glowA, radius = w * 0.85f),
            radius = w * 0.85f, center = glowA
        )
        val glowB = Offset(w * 0.02f, h * 0.88f)
        drawCircle(
            brush = Brush.radialGradient(listOf(AdNeonViolet.copy(alpha = 0.22f), Color.Transparent), center = glowB, radius = w * 0.9f),
            radius = w * 0.9f, center = glowB
        )
        // Diagonal light streaks
        listOf(
            Offset(w, h * 0.10f) to Offset(w * 0.66f, h * 0.10f + w * 0.44f),
            Offset(w, h * 0.30f) to Offset(w * 0.80f, h * 0.30f + w * 0.28f),
            Offset(0f, h * 0.70f) to Offset(w * 0.24f, h * 0.70f - w * 0.34f)
        ).forEach { (a, b) ->
            drawLine(
                brush = Brush.linearGradient(
                    listOf(Color.Transparent, AdNeonBlue.copy(alpha = 0.45f), Color.Transparent), start = a, end = b
                ),
                start = a, end = b, strokeWidth = 2.dp.toPx()
            )
        }
        // Gym graphics: one behind the header, one large and faint lower-left.
        drawDumbbell(Offset(w - 78.dp.toPx(), 58.dp.toPx()), length = 190.dp.toPx(), angle = -18f, alpha = 0.85f)
        drawDumbbell(Offset(w * 0.12f, h * 0.74f), length = w * 0.95f, angle = 28f, alpha = 0.28f)
    }
}

@Composable
private fun AttendanceDetailsHeader(onBack: () -> Unit) {
    val showTagline = LocalConfiguration.current.screenWidthDp >= 380
    Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = GymColors.Text,
                modifier = Modifier.size(28.dp).clickable(onClick = onBack)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                GymHeaderText("MEMBER PROFILE", fontSize = 22.sp)
                Text(
                    "Stronger Members  \u2022  Healthier Tomorrow",
                    color = AdNeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (showTagline) {
            Text(
                "DISCIPLINE\nBUILDS\nFREEDOM",
                color = AdNeonBlue,
                fontFamily = GymFonts.Header,
                fontWeight = FontWeight.ExtraBold,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp)
                    .graphicsLayer { rotationZ = -8f }
            )
        }
    }
}

/** Member photo (same real photo / initials fallback as [StatusRing]) in a
 *  glowing blue double ring. */
@Composable
private fun AdAvatar(member: Member, avatarSize: Dp) {
    Box(
        modifier = Modifier
            .size(avatarSize)
            .neonGlow(AdNeonBlue, alpha = 0.55f, radius = 14.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF050B1F))
            .border(3.dp, Brush.sweepGradient(listOf(AdNeonCyan, AdNeonBlue, AdNeonViolet, AdNeonCyan)), CircleShape)
            .padding(7.dp)
            .clip(CircleShape)
            .background(GymColors.Surface2),
        contentAlignment = Alignment.Center
    ) {
        val photoPath = member.photoPath
        if (photoPath != null && File(photoPath).exists()) {
            AsyncImage(
                model = File(photoPath),
                contentDescription = member.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Text(
                text = member.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString("").uppercase(),
                color = AdNeonCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        }
    }
}

@Composable
private fun AdStatusChip(status: MemberStatus) {
    val color = when (status) {
        MemberStatus.ACTIVE -> GymColors.Success
        MemberStatus.EXPIRING -> AdGold
        MemberStatus.EXPIRED -> GymColors.Danger
    }
    val icon = when (status) {
        MemberStatus.ACTIVE -> Icons.Filled.CheckCircle
        MemberStatus.EXPIRING -> Icons.Filled.HourglassBottom
        MemberStatus.EXPIRED -> Icons.Filled.Block
    }
    Row(
        modifier = Modifier
            .clip(GymShapes.pill)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.75f), GymShapes.pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            statusLabel(status), color = color, fontFamily = GymFonts.Header,
            fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.4.sp
        )
    }
}

/** Circular attendance-percentage ring: a cyan/blue gradient sweep proportional
 *  to [percent] with the percentage and days-left label centered inside. Takes
 *  an already-computed percent and never calculates one itself. */
@Composable
private fun AdPercentRing(percent: Int, daysLeftLabel: String, ringSize: Dp = 92.dp) {
    Box(modifier = Modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = this.size.width * 0.11f
            val inset = strokeWidth / 2f
            val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
            drawCircle(color = Color(0xFF06122E))
            drawArc(
                color = Color(0xFF0F2A5C), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = strokeWidth)
            )
            if (percent > 0) {
                rotate(degrees = -90f) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(AdNeonCyan, AdNeonBlue, AdNeonCyan)),
                        startAngle = 0f,
                        sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                        useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", color = GymColors.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, fontFamily = GymFonts.Header)
            Text(daysLeftLabel, color = AdLabelBlue, fontSize = 10.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun AdHeroCard(member: Member, status: MemberStatus, percent: Int, daysLeftLabel: String) {
    val shape = remember { AdHeroShape(corner = 26.dp, step = 16.dp) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xEB102A66), Color(0xEB081434), Color(0xEB0A1F52))))
            .border(1.5.dp, AdBorderBrush, shape)
            .padding(start = 16.dp, end = 14.dp, top = 26.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AdAvatar(member, 84.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                member.name, color = GymColors.Text, fontFamily = GymFonts.Header,
                fontWeight = FontWeight.Bold, fontSize = 24.sp, maxLines = 2
            )
            Text(member.phone, color = Color(0xFF4C9BFF), fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            AdStatusChip(status)
        }
        Spacer(Modifier.width(8.dp))
        AdPercentRing(percent, daysLeftLabel)
    }
}

@Composable
private fun AdSectionTitle(title: String, leading: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(10.dp))
        Text(
            title.uppercase(), color = GymColors.Text, fontFamily = GymFonts.Header,
            fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun AdCrownIcon(iconSize: Dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = this.size.width
        val h = this.size.height
        val crown = Path().apply {
            moveTo(0.06f * w, 0.78f * h)
            lineTo(0.02f * w, 0.28f * h)
            lineTo(0.28f * w, 0.50f * h)
            lineTo(0.50f * w, 0.12f * h)
            lineTo(0.72f * w, 0.50f * h)
            lineTo(0.98f * w, 0.28f * h)
            lineTo(0.94f * w, 0.78f * h)
            close()
        }
        drawPath(crown, brush = Brush.verticalGradient(listOf(Color(0xFFFFE08A), Color(0xFFF59E0B))))
        drawRoundRect(
            color = Color(0xFFF59E0B),
            topLeft = Offset(0.06f * w, 0.84f * h),
            size = Size(0.88f * w, 0.12f * h),
            cornerRadius = CornerRadius(0.06f * h)
        )
    }
}

@Composable
private fun AdDetailRow(icon: ImageVector, label: String, value: String, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tileShape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(tileShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF1B3FA8), Color(0xFF3A2A9E))))
                    .border(1.dp, AdNeonCyan.copy(alpha = 0.55f), tileShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = AdNeonCyan, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(label, color = AdLabelBlue, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value, color = GymColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().padding(start = 70.dp).height(1.dp).background(AdSoftBorder))
        }
    }
}

@Composable
private fun AdMembershipCard(member: Member) {
    Column(modifier = Modifier.fillMaxWidth().adPanel(RoundedCornerShape(24.dp)).padding(14.dp)) {
        AdSectionTitle("Membership Details") { AdCrownIcon(26.dp) }
        Spacer(Modifier.height(12.dp))
        val inner = RoundedCornerShape(18.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(inner)
                .background(Color(0x99050C24))
                .border(1.dp, AdSoftBorder, inner)
        ) {
            AdDetailRow(Icons.Filled.CurrencyRupee, "Plan", member.plan, showDivider = true)
            AdDetailRow(Icons.Filled.CalendarMonth, "Start Date", formatDate(member.joinedMillis), showDivider = true)
            AdDetailRow(Icons.Filled.CalendarMonth, "Expiry Date", formatDate(member.expiryMillis), showDivider = false)
        }
    }
}

@Composable
private fun AdProgressCard(attendedDays: Int, eligibleDays: Int) {
    val progress = if (eligibleDays > 0) (attendedDays.toFloat() / eligibleDays.toFloat()).coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier.fillMaxWidth().adPanel(RoundedCornerShape(24.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = this.size.width * 0.09f
                val inset = strokeWidth / 2f
                val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
                drawCircle(color = Color(0xFF06122E))
                drawArc(
                    brush = Brush.sweepGradient(listOf(AdNeonCyan, AdNeonBlue, AdNeonViolet, AdNeonCyan)),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = strokeWidth)
                )
            }
            Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = AdNeonCyan, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "ATTENDANCE PROGRESS", color = GymColors.Text, fontFamily = GymFonts.Header,
                fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.6.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Attended Days", color = AdLabelBlue, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("$attendedDays / $eligibleDays Days", color = GymColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(GymShapes.pill)
                    .background(Color(0xFF12305F))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(GymShapes.pill)
                        .background(Brush.horizontalGradient(listOf(AdNeonCyan, AdNeonBlue)))
                )
            }
        }
    }
}

@Composable
private fun AdHistoryHeader() {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .adPanel(shape, glow = false)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = AdGold, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "ATTENDANCE HISTORY", color = GymColors.Text, fontFamily = GymFonts.Header,
            fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun AdMonthHeader(label: String) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xE60B1836))
            .border(1.dp, AdSoftBorder, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Text(label, color = GymColors.Text, fontFamily = GymFonts.Header, fontWeight = FontWeight.Bold, fontSize = 19.sp)
    }
}

@Composable
private fun AdHistoryRow(rec: AttendanceRecord) {
    val sessionLabel = if (rec.session == AttendanceSession.MORNING.name) "Morning" else "Evening"
    // Same string the screen has always shown ("20 Sep 2026"), just laid out
    // as day | month + year.
    val dateParts = formatDate(rec.timestampMillis).split(" ")
    val rowShape = RoundedCornerShape(18.dp)
    val tileShape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .background(Color(0xE60A1631))
            .border(1.dp, AdSoftBorder, rowShape)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(tileShape)
                .background(Color(0xFF0E2350))
                .border(1.dp, AdSoftBorder, tileShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                dateParts.getOrElse(0) { "" }, color = GymColors.Text,
                fontFamily = GymFonts.Header, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(dateParts.getOrElse(1) { "" }, color = AdLabelBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(dateParts.getOrElse(2) { "" }, color = AdLabelBlue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0x3310D9A0))
                .border(1.5.dp, Color(0xFF10D9A0), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = AdTeal, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                formatTimeOfDay(rec.timestampMillis), color = AdTeal,
                fontFamily = GymFonts.Header, fontWeight = FontWeight.Bold, fontSize = 19.sp
            )
            Text(sessionLabel, color = AdLabelBlue, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
