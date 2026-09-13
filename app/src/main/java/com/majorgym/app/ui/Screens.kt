package com.majorgym.app.ui


import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.majorgym.app.MembersViewModel
import com.majorgym.app.Screen
import com.majorgym.app.data.*
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// ---------- Shared bits ----------

@Composable
fun gymFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = GymColors.Surface2,
    unfocusedContainerColor = GymColors.SurfaceCard,
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
    val targetColor = when (status) {
        MemberStatus.ACTIVE -> GymColors.Success
        MemberStatus.EXPIRING -> GymColors.Warning
        MemberStatus.EXPIRED -> GymColors.Danger
    }
    // Section 11: status color transitions smoothly (e.g. EXPIRED -> ACTIVE
    // after a renewal) instead of snapping instantly.
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = GymMotion.standardTween(),
        label = "statusRingColor"
    )
    Box(
        modifier = Modifier
            .size(size)
            .neonGlow(color, alpha = 0.30f, radius = 10.dp, shape = CircleShape)
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
                color = GymColors.Accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun StatusBadge(status: MemberStatus) {
    val (label, targetColor) = when (status) {
        MemberStatus.ACTIVE -> "ACTIVE" to GymColors.Success
        MemberStatus.EXPIRING -> "EXPIRING SOON" to GymColors.Warning
        MemberStatus.EXPIRED -> "EXPIRED" to GymColors.Danger
    }
    val color by animateColorAsState(targetColor, animationSpec = GymMotion.standardTween(), label = "statusBadgeColor")
    Box(
        modifier = Modifier
            .clip(GymShapes.pill)
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), GymShapes.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontFamily = GymFonts.Display)
    }
}

// Fix 3: custom top-edge outline for the bottom navigation dock. Replaces
// the previous plain large rounded-rectangle outline (GymShapes.xl on all
// four corners) with ONE continuous path: straight top edges over
// Dashboard/Attendance and Backup/Sync, smoothly curving (horizontal-tangent
// cubic Beziers, so there is no seam/kink where the curve meets the straight
// edges) into a shallow cradle centered on the raised Add button in the
// middle. The bottom and side corners keep the same rounding radius as
// before - only the TOP edge shape changed. Because this is applied via
// background(shape)/border(shape) rather than clip(shape), the Add button
// (drawn as ordinary Row content on top) is never cut off by the cradle -
// it simply is no longer covered by a border stroke or background fill in
// that region, which is what makes it read as notched into the surface.
private fun bottomNavCradlePath(
    size: Size,
    cornerRadiusPx: Float,
    notchHalfWidthPx: Float,
    notchFlatHalfWidthPx: Float,
    notchDepthPx: Float
): Path {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val r = cornerRadiusPx.coerceAtMost(minOf(w, h) / 2f)
    val dx = (notchHalfWidthPx - notchFlatHalfWidthPx).coerceAtLeast(1f)
    val k = dx * 0.55f // circle-approximation constant, keeps the curve's bulge natural
    return Path().apply {
        moveTo(r, 0f)
        // Straight top edge over Dashboard/Attendance, up to the cradle's left opening.
        lineTo(cx - notchHalfWidthPx, 0f)
        // Curve down into the cradle - horizontal tangent at the straight edge (cp1.y = 0)
        // and horizontal tangent at the flat bottom (cp2.y = notchDepthPx) so both joins are smooth.
        cubicTo(
            cx - notchHalfWidthPx + k, 0f,
            cx - notchFlatHalfWidthPx - k, notchDepthPx,
            cx - notchFlatHalfWidthPx, notchDepthPx
        )
        // Flat bottom of the cradle, directly under the Add button.
        lineTo(cx + notchFlatHalfWidthPx, notchDepthPx)
        // Curve back up out of the cradle, mirroring the entry curve.
        cubicTo(
            cx + notchFlatHalfWidthPx + k, notchDepthPx,
            cx + notchHalfWidthPx - k, 0f,
            cx + notchHalfWidthPx, 0f
        )
        // Straight top edge over Backup/Sync, up to the top-right corner.
        lineTo(w - r, 0f)
        arcTo(Rect(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
        lineTo(w, h - r)
        arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
        lineTo(r, h)
        arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
        lineTo(0f, r)
        arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
        close()
    }
}

private class BottomNavCradleShape(
    private val cornerRadius: Dp,
    private val notchHalfWidth: Dp,
    private val notchFlatHalfWidth: Dp,
    private val notchDepth: Dp
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = bottomNavCradlePath(
            size = size,
            cornerRadiusPx = with(density) { cornerRadius.toPx() },
            notchHalfWidthPx = with(density) { notchHalfWidth.toPx() },
            notchFlatHalfWidthPx = with(density) { notchFlatHalfWidth.toPx() },
            notchDepthPx = with(density) { notchDepth.toPx() }
        )
        return Outline.Generic(path)
    }
}

@Composable
fun BottomNav(current: Screen, modifier: Modifier = Modifier, onSelect: (Screen) -> Unit) {
    // "Members" was removed from here (spec: member management is already
    // reachable from Dashboard -> Total Members via Screen.TotalMembers,
    // which is untouched). Screen.Members and MembersScreen still exist in
    // the codebase, unmodified - this list is just where they stop being
    // reachable from.
    // Same 5 destinations, same order, same onSelect wiring as before — only
    // the visual treatment changed (glass dock + elevated center Add action,
    // matching the Stitch "Navigation Command Dock").
    val items = listOf(
        Triple(Screen.Dashboard as Screen, Icons.Filled.Dashboard, "Dashboard"),
        Triple(Screen.AttendanceLogs as Screen, Icons.Filled.FactCheck, "Attendance Logs"),
        Triple(Screen.Add as Screen, Icons.Filled.PersonAdd, "Add"),
        Triple(Screen.Backup as Screen, Icons.Filled.Storage, "Backup"),
        Triple(Screen.Sync as Screen, Icons.Filled.Sync, "Sync")
    )
    // Fix 3: the Add button sits centered in the middle slot of 5 equal-width
    // items, so its horizontal center always lands exactly on this Box's own
    // horizontal center - the cradle shape below is centered the same way,
    // so the notch always lines up with the button regardless of screen width.
    val navCradleShape = remember {
        BottomNavCradleShape(
            cornerRadius = 24.dp,
            notchHalfWidth = 32.dp,
            notchFlatHalfWidth = 16.dp,
            notchDepth = 22.dp
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Respect gesture/3-button system navigation insets so the dock
            // never sits under (or gets covered by) the system nav bar.
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            // No .clip() here on purpose: background/border are painted
            // following the cradle shape's outline, but content (the Row,
            // including the raised Add button) is left unclipped so the
            // button can visually sit in the notch instead of being cut by it.
            .background(GymColors.Bg.copy(alpha = 0.88f), navCradleShape)
            .border(1.dp, GymColors.BorderSubtle, navCradleShape)
            .padding(vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (screen, icon, label) ->
                val active = current == screen
                val isAdd = screen == Screen.Add
                // Section 6: the selected pill, icon tint, and label color all
                // ease into place instead of snapping — restrained, no bounce.
                val pillAlpha by animateFloatAsState(
                    if (active) 0.18f else 0f, animationSpec = GymMotion.standardTween(), label = "navPillAlpha"
                )
                val tint by animateColorAsState(
                    if (active) GymColors.AccentBright else GymColors.TextFaint, animationSpec = GymMotion.standardTween(), label = "navTint"
                )
                // Fix: equal-width slots via weight(1f) instead of
                // SpaceEvenly + ad hoc horizontal padding, so every icon sits
                // perfectly centered in its own slot on any screen width and
                // nothing gets clipped at the edges.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(GymShapes.md)
                        .clickable { onSelect(screen) }
                        .padding(vertical = 4.dp)
                ) {
                    if (isAdd) {
                        // Elevated central floating action button, per the
                        // Stitch dock spec — same Screen.Add destination.
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .offset(y = (-10).dp)
                                .neonGlow(GymColors.AccentBright, alpha = 0.45f, radius = 14.dp, shape = CircleShape)
                                .clip(CircleShape)
                                .background(GymColors.PrimaryGradient)
                        ) {
                            Icon(icon, contentDescription = label, tint = Color(0xFF06121A), modifier = Modifier.size(22.dp))
                        }
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(GymShapes.md)
                                .background(GymColors.Accent.copy(alpha = pillAlpha))
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            Icon(icon, contentDescription = label, tint = tint)
                        }
                    }
                    // Text requirement: only "Add" keeps a visible label now.
                    // The other four destinations stay icon-only — still
                    // clearly identifiable by their existing icons, and their
                    // content description keeps the label for accessibility.
                    if (isAdd) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = GymColors.AccentBright,
                            fontFamily = GymFonts.Display
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LabeledField(label: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(label.uppercase(), color = GymColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontFamily = GymFonts.Display, modifier = Modifier.padding(bottom = 6.dp))
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
                    val bg by animateColorAsState(
                        if (active) GymColors.Accent else GymColors.SurfaceCard,
                        animationSpec = GymMotion.standardTween(), label = "planBg"
                    )
                    val border by animateColorAsState(
                        if (active) GymColors.Accent else GymColors.Border,
                        animationSpec = GymMotion.standardTween(), label = "planBorder"
                    )
                    val textColor by animateColorAsState(
                        if (active) Color.Black else GymColors.TextMuted,
                        animationSpec = GymMotion.standardTween(), label = "planText"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(bg)
                            .border(1.dp, border, RoundedCornerShape(10.dp))
                            .clickable { onSelect(p) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(p, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            .background(GymColors.SurfaceCard)
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
        Text(formatDate(date.toMillis()), color = GymColors.Text, fontWeight = FontWeight.Medium)
    }
}

// ---------- Dashboard ----------

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DashboardScreen(members: List<Member>, holdMembersCount: Int = 0, dueMembersCount: Int = 0, onNavigate: (Screen) -> Unit) {
    val active = members.count { statusOf(it.expiryMillis) == MemberStatus.ACTIVE }
    val expiring = members.count { statusOf(it.expiryMillis) == MemberStatus.EXPIRING }
    val expired = members.count { statusOf(it.expiryMillis) == MemberStatus.EXPIRED }
    val attention = members
        .filter { m ->
            val daysRemaining = daysBetweenNow(m.expiryMillis)
            daysRemaining >= 0 && daysRemaining <= 7
        }
        .sortedBy { it.expiryMillis }

    // Features 3 & 4: privacy is a pure display preference (see
    // DashboardPrivacyPrefs) - it never touches member data, membership
    // status, Sync, Backup, fingerprint, or attendance. Read once per
    // Dashboard entry so this screen's own rendering (blank privacy view,
    // per-card number visibility) always reflects the latest saved choice -
    // the ON/OFF controls themselves now live on the Sync page header (see
    // SyncScreen), this screen only reads the resulting state.
    val context = LocalContext.current
    val privacyPrefs = remember { DashboardPrivacyPrefs(context) }
    val masterPrivacyOn by remember { mutableStateOf(privacyPrefs.masterPrivacyOn) }
    val totalVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.TOTAL)) }
    val activeVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.ACTIVE)) }
    val expiringVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.EXPIRING)) }
    val expiredVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.EXPIRED)) }
    val holdVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.HOLD)) }
    val dueVisible by remember { mutableStateOf(privacyPrefs.isNumberVisible(DashboardCard.DUE)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 90.dp)
    ) {
        item {
            // Fix 1: Dashboard header shows only the "MAJOR GYM" title now -
            // the dumbbell/gym logo box and the "Membership & Biometric
            // Kiosk" subtitle have been removed. No replacement icon or
            // subtitle was added; this is Dashboard-header-only and does not
            // touch the launcher icon, splash branding, or any other icon
            // elsewhere in the app.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GymScreenTitle("MAJOR GYM")
                Spacer(Modifier.weight(1f))
                // Relocation: the Dashboard number-visibility (gear) and
                // Dashboard Privacy (eye) controls that used to sit here have
                // moved to the Sync page header - same icons, same click
                // handlers, same DashboardPrivacyPrefs storage, just a
                // different screen. See SyncScreen.
            }
            Spacer(Modifier.height(16.dp))
        }
        if (masterPrivacyOn) {
            // Feature 4: master privacy is ON - nothing else on the Dashboard
            // renders. No member counts, no Hold/Due rows, no attention list.
            // This is purely visual: nothing is deleted, disabled, or changed -
            // turning it back off (the eye icon, now on the Sync page header)
            // instantly restores everything exactly as it was, including each
            // card's own individual ON/OFF choice from Feature 3.
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = GymColors.TextFaint, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Dashboard Privacy Mode is ON", color = GymColors.TextFaint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else {
        item {
            // Fix 2: Row height is driven by IntrinsicSize.Min and each
            // StatCard fills that height, so both cards in a row always
            // share the same height and the centering inside StatCard has
            // real vertical room to work with (not just a wrap-content box).
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp).height(IntrinsicSize.Min)) {
                // Fix #1 / Feature 3: Total Members keeps its button/tap
                // behavior (still opens the full member list) regardless -
                // only whether the number itself is shown is now the
                // owner's own per-card choice (see the settings gear above).
                StatCard("Total Members", if (totalVisible) members.size.toString() else null, Modifier.weight(1f).fillMaxHeight()) { onNavigate(Screen.TotalMembers) }
                StatCard("Active", if (activeVisible) active.toString() else null, Modifier.weight(1f).fillMaxHeight()) { onNavigate(Screen.ActiveMembers) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp).height(IntrinsicSize.Min)) {
                StatCard("Expiring Soon", if (expiringVisible) expiring.toString() else null, Modifier.weight(1f).fillMaxHeight()) { onNavigate(Screen.ExpiringMembers) }
                StatCard("Expired", if (expiredVisible) expired.toString() else null, Modifier.weight(1f).fillMaxHeight()) { onNavigate(Screen.ExpiredMembers) }
            }
        }
        item {
            // Hold Members (fix #5): a separate entry point for members
            // expired more than 2 months with no renewal - preserved in full,
            // just hidden from the normal Members list/counts above.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GymColors.SurfaceCard)
                    .border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))
                    .clickable { onNavigate(Screen.HoldMembers) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(GymColors.Warning.copy(alpha = 0.15f))
                        .padding(10.dp)
                ) {
                    Icon(Icons.Filled.Pause, contentDescription = null, tint = GymColors.Warning, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hold Members", color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Expired 2+ months, not yet renewed",
                        color = GymColors.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (holdVisible) {
                    Text(holdMembersCount.toString(), color = GymColors.Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = GymColors.TextFaint)
            }
            Spacer(Modifier.height(14.dp))
        }
        item {
            // Feature 1: Due Members - a payment-status filter, independent
            // of membership status. An ACTIVE member with a due amount still
            // appears in Active/Total Members above as well as here.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GymColors.SurfaceCard)
                    .border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))
                    .clickable { onNavigate(Screen.DueMembers) }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(GymColors.Danger.copy(alpha = 0.15f))
                        .padding(10.dp)
                ) {
                    Icon(Icons.Filled.CurrencyRupee, contentDescription = null, tint = GymColors.Danger, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Due Members", color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Members with an outstanding due amount",
                        color = GymColors.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (dueVisible) {
                    Text(dueMembersCount.toString(), color = GymColors.Danger, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = GymColors.TextFaint)
            }
            Spacer(Modifier.height(14.dp))
        }
        if (attention.isNotEmpty()) {
            item {
                Text("NEEDS ATTENTION (${attention.size})", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            items(attention, key = { it.id }) { m ->
                val status = statusOf(m.expiryMillis)
                val days = daysBetweenNow(m.expiryMillis)
                Row(
                    modifier = Modifier
                        .animateItemPlacement()
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(GymColors.SurfaceCard)
                        .border(1.dp, GymColors.Border, RoundedCornerShape(14.dp))
                        .clickable { onNavigate(Screen.Profile(m.id)) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusRing(m.photoPath, m.name, status, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (days < 0) "Expired ${-days}d ago" else "Expires in ${days}d",
                            color = GymColors.TextMuted, fontSize = 12.sp
                        )
                    }
                    StatusBadge(status)
                }
            }
        }
        } // end of `else` (masterPrivacyOn == false) started above
    }
}

/** One row of the Feature 3 settings dialog: a card's name plus its own
 *  independent ON/OFF [Switch] for whether its number is shown. Not private
 *  so SyncScreen (which now hosts the settings dialog that uses this, after
 *  the header-controls relocation) can reuse it as-is instead of duplicating it. */
@Composable
fun DashboardVisibilityRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = GymColors.Text, fontSize = 13.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = GymColors.Accent, checkedTrackColor = GymColors.Accent.copy(alpha = 0.5f))
        )
    }
}

/**
 * Reached from the Dashboard's "Open Attendance Scanner" card. Just a thin
 * page shell (header + back) around the existing [GymAttendanceQrCard] —
 * the QR generation, display, and share logic is untouched and not
 * duplicated here.
 */
@Composable
fun AttendanceScreen(onNavigate: (Screen) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            IconButton(onClick = { onNavigate(Screen.Dashboard) }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = GymColors.Text)
            }
            Spacer(Modifier.width(4.dp))
            Text("Attendance", color = GymColors.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        GymAttendanceQrCard()
    }
}

/**
 * The gym's fixed attendance QR: display + share only, unchanged. Reached
 * via [AttendanceScreen] instead of always rendering on the Dashboard.
 */
@Composable
fun GymAttendanceQrCard() {
    val context = LocalContext.current
    var fullScreen by remember { mutableStateOf(false) }
    val qrBitmap = remember { QrUtils.gymQrBitmap(QrUtils.GYM_ATTENDANCE_CODE) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GymShapes.lg)
            .background(GymColors.CardGlassGradient)
            .border(1.dp, GymColors.BorderGlowCyan, GymShapes.lg)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GymSectionLabel("Gym Attendance QR", modifier = Modifier.align(Alignment.Start), color = GymColors.Accent)
        Text(
            "Members scan this to check in \u2014 fixed, never changes",
            color = GymColors.TextFaint, fontSize = 11.sp, modifier = Modifier.align(Alignment.Start).padding(top = 2.dp, bottom = 12.dp)
        )
        Box(
            modifier = Modifier
                .size(176.dp)
                .neonGlow(GymColors.AccentBright, alpha = 0.32f, radius = 16.dp, shape = GymShapes.lg)
                .clip(GymShapes.lg)
                .background(GymColors.PrimaryGradient)
                .padding(5.dp)
                .clip(GymShapes.md)
                .background(Color.White)
                .padding(10.dp)
                .clickable { fullScreen = true },
            contentAlignment = Alignment.Center
        ) {
            Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Gym attendance QR code")
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(Icons.Filled.Fullscreen, "Display", GymColors.Accent, Modifier.weight(1f)) { fullScreen = true }
            ActionButton(Icons.Filled.Share, "Share", GymColors.Accent, Modifier.weight(1f)) {
                QrShareUtils.shareBitmap(context, qrBitmap, "gym_attendance_qr.png", "Share gym attendance QR")
            }
        }
    }

    if (fullScreen) {
        Dialog(onDismissRequest = { fullScreen = false }) {
            // Section 17/21: restrained fade + scale entrance, consistent with
            // the photo viewer's existing custom fade. The QR itself renders
            // immediately at full opacity of its own bitmap — only the
            // container animates, so the code stays instantly scannable.
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(GymMotion.standardTween()) + androidx.compose.animation.scaleIn(initialScale = 0.94f, animationSpec = GymMotion.standardTween())
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Gym attendance QR code",
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("MAJOR GYM \u2014 Scan to mark attendance", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    // Section 7: count up rather than instantly replacing the digits, but
    // only animate when the underlying number actually changed — a
    // non-numeric value (shouldn't happen here, but defensively) just
    // displays as-is instead of animating from 0.
    val intValue = value?.toIntOrNull()
    val displayText = if (intValue != null) {
        val animated by animateIntAsState(
            targetValue = intValue,
            animationSpec = GymMotion.standardTween(),
            label = "statCardCount"
        )
        animated.toString()
    } else value

    // Priority 3: a very subtle press scale on clickable stat cards, same
    // restrained treatment as the primary CTA buttons — only when the card
    // is actually clickable.
    val cardInteractionSource = remember { MutableInteractionSource() }
    // Fix 2: title + number are wrapped in a Box that fills the card and
    // centers its content both horizontally and vertically (Alignment.Center
    // on the Box, plus horizontalAlignment.CenterHorizontally on the inner
    // Column so multi-line/short text stays centered too). Card dimensions,
    // padding, shape, colors, font sizes/weights and the existing
    // title-number spacing are all unchanged - only the alignment changed.
    Box(
        modifier = modifier
            .clip(GymShapes.lg)
            .background(GymColors.CardGlassGradient)
            .border(1.dp, GymColors.Border, GymShapes.lg)
            .let {
                if (onClick != null)
                    it.clickable(interactionSource = cardInteractionSource, indication = null) { onClick() }
                        .gymPressScale(cardInteractionSource)
                else it
            }
            .padding(16.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(), color = GymColors.TextFaint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, fontFamily = GymFonts.Display, textAlign = TextAlign.Center)
            // Fix #1 (Total Members): a null value means "don't show a count at
            // all" - the card still renders (and is still tappable) with just
            // its label, instead of a number row.
            if (displayText != null) {
                Spacer(Modifier.height(8.dp))
                Text(displayText, color = GymColors.Accent, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = GymFonts.Display, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------- Members list ----------

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MembersScreen(members: List<Member>, onNavigate: (Screen) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = members.filter {
        it.name.contains(query, ignoreCase = true) || it.phone.contains(query) || it.idProof.contains(query, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        GymScreenTitle("MEMBERS")
        Spacer(Modifier.height(12.dp))
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
        Spacer(Modifier.height(14.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
            items(filtered, key = { it.id }) { m ->
                MemberRow(m, onNavigate, Modifier.animateItemPlacement())
            }
        }
    }
}

/**
 * A single member row: status ring, name, phone/plan, expiry date, status
 * badge, and a Renew shortcut — tapping the row opens the member's profile.
 * Extracted from [MembersScreen] so the Dashboard's four filtered list pages
 * (Total/Active/Expiring/Expired) can reuse the exact same member-card UI
 * and the exact same profile/renew navigation, instead of a second copy.
 */
@Composable
fun MemberRow(m: Member, onNavigate: (Screen) -> Unit, modifier: Modifier = Modifier, showDueAmount: Boolean = false) {
    val status = statusOf(m.expiryMillis)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(GymShapes.lg)
            .background(GymColors.CardGlassGradient)
            .border(1.dp, GymColors.Border, GymShapes.lg)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable { onNavigate(Screen.Profile(m.id)) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusRing(m.photoPath, m.name, status, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(m.name, color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${m.phone} \u00B7 ${m.plan}", color = GymColors.TextMuted, fontSize = 12.sp)
                Text("Expires ${formatDate(m.expiryMillis)}", color = GymColors.TextFaint, fontSize = 11.sp)
                // Feature 1 (Due Members): only shown on the Due Members list -
                // other lists (Active/Expiring/Expired/Total) stay exactly as
                // they were, per "use the existing Member UI wherever possible."
                if (showDueAmount && m.fee > 0.0) {
                    Text("Due: ${formatMoney(m.fee)}", color = GymColors.Danger, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            StatusBadge(status)
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(GymShapes.pill)
                    .background(GymColors.Accent.copy(alpha = 0.15f))
                    .border(1.dp, GymColors.Accent.copy(alpha = 0.45f), GymShapes.pill)
                    .clickable { onNavigate(Screen.Renew(m.id)) }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text("Renew", color = GymColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = GymFonts.Display)
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
        uri?.let { vm.savePhoto(id, it) { path -> photoPath = path } }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingCameraUri?.let { vm.savePhoto(id, it) { path -> photoPath = path } }
    }
    val requestCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = newCameraCaptureUri(context)
            pendingCameraUri = uri
            takePhoto.launch(uri)
        }
    }

    val pickIdPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { vm.saveIdProofPhoto(id, it) { path -> idProofPhotoPath = path } }
    }
    val takeIdPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingIdCameraUri?.let { vm.saveIdProofPhoto(id, it) { path -> idProofPhotoPath = path } }
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
    // Fix #2: Due Amount is optional - no longer part of the validity gate.
    val valid = name.trim().length >= 3 && phone.length >= 10 && !phoneTaken

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Keyboard-aware scrolling: keeps the focused field (and, once the
            // user scrolls, the final Save/Add button) reachable above the IME
            // instead of the keyboard simply covering the bottom of the screen.
            // Paired with android:windowSoftInputMode="adjustResize" on the
            // activity so the inset this reserves space for is accurate.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            // Fix: reserved bottom space increased so the Add/Save button is
            // never left underneath the floating bottom nav dock.
            .padding(top = 20.dp, bottom = 140.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ArrowBack, null, tint = GymColors.Text,
                modifier = Modifier.clickable { onNavigate(existing?.let { Screen.Profile(it.id) } ?: Screen.Members) }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (existing == null) "ADD MEMBER" else "EDIT MEMBER",
                color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp,
                fontFamily = GymFonts.Display
            )
        }
        Spacer(Modifier.height(20.dp))

        GymSectionLabel("Enrollment Telemetry", modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .size(104.dp)
                .align(Alignment.CenterHorizontally)
                .neonGlow(GymColors.AccentBright, alpha = 0.30f, radius = 14.dp, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(GymColors.SurfaceCard)
                    .border(2.dp, GymColors.AccentBright, CircleShape)
                    .clickable { showPhotoSourceSheet = true },
                contentAlignment = Alignment.Center
            ) {
                val p = photoPath
                if (p != null && File(p).exists()) {
                    AsyncImage(model = File(p), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = GymColors.Accent, modifier = Modifier.size(32.dp))
                }
            }
            // Small camera badge, matching the Stitch "BIO-CAM" capsule accent.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(GymColors.PrimaryGradient)
                    .clickable { showPhotoSourceSheet = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = Color(0xFF06121A), modifier = Modifier.size(15.dp))
            }
        }
        Text(
            "Profile Photo \u00B7 Face Recognition Ready", color = GymColors.TextFaint, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 20.dp)
        )

        LabeledField("Full Name") {
            OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = gymFieldColors())
        }
        LabeledField("Phone Number") {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.filter { c -> c.isDigit() }.take(10) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = gymFieldColors(),
                isError = phoneTaken
            )
            AnimatedVisibility(
                visible = phoneTaken,
                enter = fadeIn(GymMotion.standardTween()) + expandVertically(GymMotion.standardTween()),
                exit = fadeOut(GymMotion.fastTween()) + shrinkVertically(GymMotion.fastTween())
            ) {
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
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = gymFieldColors(),
                isError = idProofError
            )
            AnimatedVisibility(
                visible = idProofError,
                enter = fadeIn(GymMotion.standardTween()) + expandVertically(GymMotion.standardTween()),
                exit = fadeOut(GymMotion.fastTween()) + shrinkVertically(GymMotion.fastTween())
            ) {
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
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(GymShapes.md)
                        .background(GymColors.Bg)
                        .border(1.dp, GymColors.BorderSubtle, GymShapes.md)
                        .clickable { showIdPhotoSourceSheet = true }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Icon(Icons.Filled.Badge, null, tint = GymColors.Violet, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.height(6.dp))
                        Text("Tap to add ID Proof Photo", color = GymColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Fix #3: Passkey generation/hashing/storage is untouched (see
        // `passkey` above and `passwordHash = PasskeyUtils.hash(passkey)`
        // below) - only this owner-facing UI field is removed. The passkey
        // itself still exists internally and is still sent to the member via
        // WhatsApp after saving (see RegistrationSuccessScreen/WhatsAppShare),
        // it's simply never shown on any screen anymore.
        LabeledField("Joining Date") { DatePickerField(joined) { joined = it } }
        LabeledField("Membership Plan") { PlanGrid(plan) { plan = it } }
        // Fix #2: renamed from "Fee" to "Due Amount" and made optional - the
        // owner must be able to add a member without entering one at all.
        LabeledField("Due Amount (\u20B9) (Optional)") {
            OutlinedTextField(
                value = fee,
                onValueChange = { fee = it.filter { c -> c.isDigit() } },
                placeholder = { Text("Enter Due Amount (Optional)", color = GymColors.TextFaint) },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = gymFieldColors()
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(GymShapes.md).background(GymColors.Success.copy(alpha = 0.14f)).border(1.dp, GymColors.Success.copy(alpha = 0.35f), GymShapes.md).padding(14.dp)
        ) {
            Icon(Icons.Filled.Schedule, null, tint = GymColors.Success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                GymSectionLabel("Calculated expiry projection", color = GymColors.Success.copy(alpha = 0.8f))
                Text(formatDate(expiryMillis), color = GymColors.Success, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = GymFonts.Display)
            }
        }
        Spacer(Modifier.height(20.dp))

        PrimaryButton(
            text = if (existing == null) "Add Member" else "Save Changes",
            enabled = valid,
            icon = Icons.Filled.PersonAddAlt,
            onClick = {
                // Fix #2: Due Amount is optional - blank/invalid input just
                // means "no due amount entered", not a save failure.
                val feeVal = fee.toDoubleOrNull() ?: 0.0
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
                    qrToken = existing?.qrToken ?: QrUtils.freshToken(),
                    qrTokenExpiryMillis = existing?.qrTokenExpiryMillis
                        ?: (System.currentTimeMillis() + QrUtils.TOKEN_VALIDITY_MILLIS),
                    idProof = idProof,
                    idProofPhotoPath = idProofPhotoPath,
                    // Bug fix: this constructor previously omitted fingerprintTemplate,
                    // so it silently fell back to the Member() default of null on every
                    // save — wiping out an enrolled fingerprint any time the member was
                    // edited. Carry the existing template forward untouched; it is only
                    // ever changed via the dedicated enroll/replace/remove fingerprint
                    // actions (see MembersViewModel.saveFingerprintTemplate / removeFingerprintTemplate).
                    fingerprintTemplate = existing?.fingerprintTemplate,
                    // A Hold member being edited (not renewed) stays on Hold -
                    // only the explicit Renew flow (see RenewScreen) or the
                    // daily lifecycle worker moves them back to normal.
                    membershipState = existing?.membershipState ?: MembershipState.ACTIVE
                )
                vm.save(member)
                if (existing == null) onNavigate(Screen.Registered(id, passkey)) else onNavigate(Screen.Profile(id))
            }
        )
    }

    if (showPhotoSourceSheet) {
        AlertDialog(
            onDismissRequest = { showPhotoSourceSheet = false },
            containerColor = GymColors.SurfaceCard,
            title = { Text("Add Photo", color = GymColors.Text, fontWeight = FontWeight.Bold) },
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
            containerColor = GymColors.SurfaceCard,
            title = { Text("ID Proof Photo", color = GymColors.Text, fontWeight = FontWeight.Bold) },
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
            containerColor = GymColors.SurfaceCard,
            title = { Text("Remove ID Proof Photo?", color = GymColors.Text, fontWeight = FontWeight.Bold) },
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
    var confirmRemoveFingerprint by remember { mutableStateOf(false) }
    var showPhoto by remember { mutableStateOf(false) }
    var showIdPhoto by remember { mutableStateOf(false) }
    val status = statusOf(member.expiryMillis)
    val days = daysBetweenNow(member.expiryMillis)
    val history = remember(member.historyJson) { member.historyJson.toHistoryList().reversed() }
    val lastRenewedMillis = remember(history) { history.firstOrNull { it.type == "Renewed" }?.dateMillis }
    // Feature 1 (Due Members / Due Payment): local state for the "Amount
    // Paid" field, keyed to this member so switching to a different
    // member's profile always starts with a clean, empty field.
    var amountPaidText by remember(member.id) { mutableStateOf("") }
    var paymentError by remember(member.id) { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 90.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(Icons.Filled.ArrowBack, null, tint = GymColors.Text, modifier = Modifier.clickable { onNavigate(Screen.Members) })
                Spacer(Modifier.width(10.dp))
                Text("MEMBER PROFILE", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp, fontFamily = GymFonts.Display)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Box(modifier = Modifier.clickable { showPhoto = true }) {
                    StatusRing(member.photoPath, member.name, status, 96.dp)
                }
                Spacer(Modifier.height(12.dp))
                Text(member.name, color = GymColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                StatusBadge(status)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (days < 0) "Expired ${-days} day(s) ago" else "$days day(s) remaining",
                    color = GymColors.TextFaint, fontSize = 12.sp
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(GymColors.SurfaceCard)
                    .border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                ProfileRow(Icons.Filled.Phone, "Phone", member.phone)
                ProfileRow(Icons.Filled.CalendarToday, "Joined", formatDate(member.joinedMillis))
                if (lastRenewedMillis != null) {
                    ProfileRow(Icons.Filled.Refresh, "Renewed", formatDate(lastRenewedMillis))
                }
                ProfileRow(Icons.Filled.CalendarToday, "Expires", formatDate(member.expiryMillis))
                ProfileRow(Icons.Filled.CurrencyRupee, "Current Plan", member.plan)
                // Feature 1: Due Amount shown separately from the plan now
                // that it has its own payment flow below (still the same
                // underlying Member.fee field, so nothing else changes).
                ProfileRow(
                    Icons.Filled.CurrencyRupee, "Due Amount", formatMoney(member.fee),
                    valueColor = if (member.fee > 0.0) GymColors.Danger else GymColors.Success
                )
                ProfileRow(Icons.Filled.Badge, "ID Proof", member.idProof.ifBlank { "Not Provided" })
                ProfileRow(Icons.Filled.Fingerprint, "Fingerprint", if (member.fingerprintTemplate != null) "Enrolled" else "Not Enrolled", last = true)
            }
            // Feature 1: Due Payment - only shown while there's actually
            // something due, so it naturally disappears the moment the due
            // amount reaches zero (paid in full), without needing any other
            // screen change. Never touches membership status, plan, expiry,
            // or history - purely adjusts the due balance.
            if (member.fee > 0.0) {
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GymColors.SurfaceCard)
                        .border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text("DUE PAYMENT", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Due Amount", color = GymColors.TextMuted, fontSize = 13.sp)
                        Text(formatMoney(member.fee), color = GymColors.Danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    LabeledField("Amount Paid (\u20B9)") {
                        OutlinedTextField(
                            value = amountPaidText,
                            onValueChange = { amountPaidText = it.filter { c -> c.isDigit() }; paymentError = null },
                            placeholder = { Text("Enter amount being paid now", color = GymColors.TextFaint) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = gymFieldColors()
                        )
                    }
                    if (paymentError != null) {
                        Text(paymentError!!, color = GymColors.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(
                        text = "Save Payment",
                        onClick = {
                            val paid = amountPaidText.toDoubleOrNull() ?: 0.0
                            when {
                                paid <= 0.0 -> paymentError = "Enter an amount to record a payment."
                                // Overpayment protection: never allow a negative
                                // due amount to be stored - block the entry with
                                // a clear message instead of silently capping it,
                                // so the owner notices and can correct it.
                                paid > member.fee -> paymentError = "Amount paid cannot exceed the due amount (${formatMoney(member.fee)})."
                                else -> {
                                    val newDue = (member.fee - paid).coerceAtLeast(0.0)
                                    vm.save(member.copy(fee = newDue, updatedAtMillis = System.currentTimeMillis()))
                                    amountPaidText = ""
                                    paymentError = null
                                }
                            }
                        }
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
                    Text("ID PROOF PHOTO", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(10.dp))
                    val idPhotoFile = member.idProofPhotoPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
                    if (idPhotoFile != null) {
                        AsyncImage(
                            model = idPhotoFile, contentDescription = "ID proof photo", contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showIdPhoto = true }
                        )
                    } else {
                        Text("No ID Proof Photo", color = GymColors.TextFaint, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                ActionButton(Icons.Filled.Refresh, "Renew", GymColors.Accent, Modifier.weight(1f)) { onNavigate(Screen.Renew(member.id)) }
                ActionButton(Icons.Filled.Edit, "Edit", GymColors.TextMuted, Modifier.weight(1f)) { onNavigate(Screen.Edit(member.id)) }
                ActionButton(Icons.Filled.Delete, "Delete", GymColors.Danger, Modifier.weight(1f)) { confirmDelete = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
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
            // Fix #11: a clear, explicit way to remove a fingerprint without
            // deleting the member - gated behind its own confirmation so it
            // can't be tapped by accident on a kiosk-adjacent device.
            if (member.fingerprintTemplate != null) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    ActionButton(Icons.Filled.Fingerprint, "Remove Fingerprint", GymColors.Danger, Modifier.weight(1f)) {
                        confirmRemoveFingerprint = true
                    }
                }
            }
            Text("HISTORY", color = GymColors.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(history) { h ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GymColors.SurfaceCard)
                    .border(1.dp, GymColors.Border, RoundedCornerShape(12.dp))
                    .padding(12.dp),
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
            containerColor = GymColors.SurfaceCard,
            title = { Text("Delete member", color = GymColors.Text, fontWeight = FontWeight.Bold) },
            text = { Text("Delete ${member.name}? This cannot be undone.", color = GymColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = { vm.delete(member); onNavigate(Screen.Members) }) { Text("Delete", color = GymColors.Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = GymColors.TextMuted) }
            }
        )
    }

    if (confirmRemoveFingerprint) {
        AlertDialog(
            onDismissRequest = { confirmRemoveFingerprint = false },
            containerColor = GymColors.SurfaceCard,
            title = { Text("Remove fingerprint", color = GymColors.Text, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Remove ${member.name}'s enrolled fingerprint? They'll need to re-enroll to check in by fingerprint again.",
                    color = GymColors.TextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Clears the stored template and bumps updatedAtMillis
                    // (see MembersViewModel.clearFingerprintTemplate), which
                    // also propagates the removal through sync so a stale
                    // template on another paired device doesn't linger there.
                    vm.clearFingerprintTemplate(member)
                    confirmRemoveFingerprint = false
                }) { Text("Remove", color = GymColors.Danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveFingerprint = false }) { Text("Cancel", color = GymColors.TextMuted) }
            }
        )
    }
}

@Composable
fun ProfileRow(icon: ImageVector, label: String, value: String, last: Boolean = false, valueColor: Color = GymColors.Text) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = GymColors.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = GymColors.TextFaint, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    if (!last) Divider(color = GymColors.BorderSubtle, thickness = 1.dp)
}

@Composable
fun ActionButton(icon: ImageVector, label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .gymPressScale(interaction)
            .clip(GymShapes.md)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), GymShapes.md)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = GymFonts.Display)
    }
}

// ---------- Renew ----------

@Composable
fun RenewScreen(member: Member, vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    var plan by remember { mutableStateOf(member.plan) }
    var fee by remember { mutableStateOf(member.fee.toInt().toString()) }
    val today = LocalDate.now().toMillis()
    // Feature 2: the owner can now pick any date the renewed membership
    // should start from, instead of it always being forced to today/the
    // current expiry. Defaults to exactly the same date the old hardcoded
    // logic used to compute automatically (today, or the current expiry if
    // it's still in the future) - so a renewal where the owner never
    // touches this field behaves identically to before.
    var startDate by remember { mutableStateOf((if (member.expiryMillis > today) member.expiryMillis else today).toLocalDate()) }
    val months = PLAN_MONTHS[plan] ?: 1L
    val newExpiry = addMonthsMillis(startDate.toMillis(), months)

    // Fix (per request): Renew screen made scrollable the same way Add/Edit
    // Member already is, with imePadding for the Fee field's keyboard and
    // enough reserved bottom space that Confirm Renewal is never hidden
    // behind the floating bottom nav dock.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp, bottom = 140.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Icon(Icons.Filled.ArrowBack, null, tint = GymColors.Text, modifier = Modifier.clickable { onNavigate(Screen.Profile(member.id)) })
            Spacer(Modifier.width(10.dp))
            Text("RENEW MEMBERSHIP", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp, fontFamily = GymFonts.Display)
        }
        FuturisticCard(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusRing(member.photoPath, member.name, statusOf(member.expiryMillis), 52.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(member.name, color = GymColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    GymSectionLabel("Current membership")
                    Text("Expires ${formatDate(member.expiryMillis)}", color = GymColors.TextMuted, fontSize = 12.sp)
                }
            }
        }
        LabeledField("Renewal Plan") { PlanGrid(plan) { plan = it } }
        // Feature 2: Select Start Date - the new expiry below is always
        // computed from this date + the selected plan's duration, and this
        // exact date is what actually gets saved as this renewal cycle's
        // start (see the Save button below) - not just shown on screen.
        LabeledField("Select Start Date") { DatePickerField(startDate) { startDate = it } }
        LabeledField("Fee (\u20B9)") {
            OutlinedTextField(
                value = fee,
                onValueChange = { fee = it.filter { c -> c.isDigit() } },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(10.dp), colors = gymFieldColors()
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(GymShapes.md).background(GymColors.Success.copy(alpha = 0.14f)).border(1.dp, GymColors.Success.copy(alpha = 0.35f), GymShapes.md).padding(14.dp)
        ) {
            Icon(Icons.Filled.EventAvailable, null, tint = GymColors.Success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                GymSectionLabel("New expiry date", color = GymColors.Success.copy(alpha = 0.8f))
                Text(formatDate(newExpiry), color = GymColors.Success, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = GymFonts.Display)
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = "Confirm Renewal",
            icon = Icons.Filled.Refresh,
            onClick = {
                val feeVal = fee.toDoubleOrNull() ?: 0.0
                // Feature 2: the history entry's date is the selected start
                // date (not "whenever the Save button happened to be
                // tapped") - this is what ProfileScreen's "Renewed" row and
                // the History list both read back, so the chosen date is
                // genuinely part of the member's saved membership data, not
                // just a number shown once on this screen.
                val newHistory = member.historyJson.toHistoryList() + HistoryEntry("Renewed", plan, feeVal, startDate.toMillis(), newExpiry)
                vm.save(
                    member.copy(
                        plan = plan, fee = feeVal, expiryMillis = newExpiry, historyJson = newHistory.toJson(),
                        updatedAtMillis = System.currentTimeMillis(),
                        qrToken = QrUtils.freshToken(),
                        qrTokenExpiryMillis = System.currentTimeMillis() + QrUtils.TOKEN_VALIDITY_MILLIS,
                        // Fix #6: a Hold member renewing returns straight to
                        // the normal Members list - same Member ID, no
                        // duplication, fingerprint becomes searchable again
                        // immediately (see FingerprintKioskService's cache
                        // filter, which re-includes them the instant this
                        // becomes ACTIVE).
                        membershipState = MembershipState.ACTIVE
                    )
                )
                onNavigate(Screen.Renewed(member.id, justRenewed = true))
            }
        )
    }
}

// ---------- Renewal / QR success ----------

@Composable
fun RenewalSuccessScreen(member: Member, justRenewed: Boolean = false, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val qrBitmap = remember(member.qrToken) { QrUtils.memberQrBitmap(member) }
    val valid = QrUtils.isTokenValid(member)

    // Section 15: same premium staggered treatment as registration success,
    // for consistency (checkmark -> message -> QR -> actions).
    var showCheck by remember { mutableStateOf(false) }
    var showMessage by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showCheck = true
        delay(80)
        showMessage = true
        delay(80)
        showQr = true
        delay(100)
        showActions = true
    }

    // Fix 4: added verticalScroll so the Done button can never end up hidden
    // below the visible screen area on shorter devices. verticalArrangement =
    // Center is kept - combined with fillMaxSize, content shorter than the
    // screen still renders centered exactly as before, while content taller
    // than the screen (small devices/large font settings) becomes scrollable
    // so Done is always reachable.
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedVisibility(
            visible = showCheck,
            enter = androidx.compose.animation.scaleIn(animationSpec = spring(dampingRatio = 0.6f, stiffness = 380f)) + fadeIn(GymMotion.standardTween())
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = GymColors.Success, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(12.dp))
        AnimatedVisibility(visible = showMessage, enter = fadeIn(GymMotion.standardTween()) + expandVertically(GymMotion.standardTween())) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (justRenewed) "MEMBERSHIP RENEWED" else "QR UPDATED", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp, fontFamily = GymFonts.Display)
                Text(member.name, color = GymColors.TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(24.dp))

        AnimatedVisibility(
            visible = showQr,
            enter = fadeIn(GymMotion.standardTween()) + androidx.compose.animation.scaleIn(initialScale = 0.92f, animationSpec = GymMotion.standardTween())
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(232.dp)
                        .neonGlow(GymColors.AccentBright, alpha = 0.30f, radius = 16.dp, shape = GymShapes.lg)
                        .clip(GymShapes.lg)
                        .background(GymColors.PrimaryGradient)
                        .padding(6.dp)
                        .clip(GymShapes.md)
                        .background(Color.White)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Member QR code")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (valid) "Valid until ${formatDateTime(member.qrTokenExpiryMillis)}" else "Expired \u2014 regenerate before sharing",
                    color = if (valid) GymColors.TextFaint else GymColors.Danger,
                    fontSize = 11.sp
                )
                Text(
                    "This QR replaces any earlier one \u2014 old QRs no longer work.",
                    color = GymColors.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(28.dp))

        AnimatedVisibility(visible = showActions, enter = fadeIn(GymMotion.standardTween()) + expandVertically(GymMotion.standardTween())) {
            if (justRenewed) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(text = "Share Renewal Update", icon = Icons.Filled.Share, onClick = { WhatsAppShare.shareRenewal(context, member) })
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { onNavigate(Screen.Profile(member.id)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Done", color = GymColors.Text)
                    }
                }
            } else {
                PrimaryButton(text = "Done", onClick = { onNavigate(Screen.Profile(member.id)) })
            }
        }
    }
}

// ---------- Backup ----------

@Composable
fun BackupScreen(vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var shareMessage by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var latestBackup by remember { mutableStateOf(vm.latestBackupFile()) }

    fun refreshLatestBackup() { latestBackup = vm.latestBackupFile() }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) {
            exporting = true
            vm.exportZipBackup { file, error ->
                exporting = false
                if (file != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    message = "Backup exported."
                    messageIsError = false
                    refreshLatestBackup()
                } else {
                    message = "Backup failed\n\n${error ?: "The backup could not be completed."}\n\nYour previous backup has been kept safe."
                    messageIsError = true
                }
            }
        }
    }
    // Accepts both the new ".zip" backups and legacy ".json" backups (section 6/22)
    // - format is actually detected from file content, not the extension, so this
    // mime-type filter is just to keep the picker's list relevant, not authoritative.
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            vm.importBackup(uri) { outcome ->
                when (outcome) {
                    is RestoreOutcome.Success -> {
                        message = "Records restored." +
                            if (outcome.attendanceRestored > 0) " ${outcome.attendanceRestored} attendance record(s) restored." else ""
                        messageIsError = false
                        refreshLatestBackup()
                    }
                    is RestoreOutcome.InvalidBackup -> {
                        message = outcome.reason
                        messageIsError = true
                    }
                    is RestoreOutcome.RestoreFailed -> {
                        message = "Restore failed\n\n${outcome.reason}"
                        messageIsError = true
                    }
                }
            }
        }
    }

    // Section 17 (non-negotiable): the whole screen must scroll, and it must
    // keep scrolling correctly as more sections get added later — so this
    // wraps the entire content area in a single Column + verticalScroll, with
    // no fixed-height parent anywhere inside it, exactly the way BottomNav's
    // own fixed overlay (drawn separately in MainActivity, not part of this
    // Column) already coexists with scrollable screens elsewhere in the app.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(top = 20.dp, bottom = 90.dp)
    ) {
        GymScreenTitle("BACKUP & RESTORE")
        Spacer(Modifier.height(20.dp))

        // Fix #8: Attendance Scanner access moved here from the Dashboard -
        // same GymAttendanceQrCard-backed AttendanceScreen as before, nothing
        // about the scanner itself changed, only where it's reached from.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .neonGlow(GymColors.AccentBright, alpha = 0.20f, shape = GymShapes.lg)
                .clip(GymShapes.lg)
                .background(GymColors.CardGlassGradient)
                .border(1.dp, GymColors.BorderGlowCyan, GymShapes.lg)
                .clickable { onNavigate(Screen.Attendance) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(GymShapes.md)
                    .background(GymColors.PrimaryGradient)
                    .padding(10.dp)
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = Color(0xFF06121A), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Open Attendance Scanner", color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Scan member QR codes and manage check-ins",
                    color = GymColors.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = GymColors.TextFaint)
        }
        Spacer(Modifier.height(14.dp))

        FuturisticCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Download, null, tint = GymColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export All Records", color = GymColors.Text, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            val exportInteractionSource = remember { MutableInteractionSource() }
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .gymPressScale(exportInteractionSource)
                    .clip(GymShapes.md)
                    .background(if (!exporting) GymColors.PrimaryGradient else Brush.linearGradient(listOf(GymColors.Surface3, GymColors.Surface3)))
                    .clickable(interactionSource = exportInteractionSource, indication = null, enabled = !exporting) {
                        val label = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))
                        createDoc.launch("MajorGym_Backup_$label.zip")
                    }
            ) {
                AnimatedContent(
                    targetState = exporting,
                    transitionSpec = { fadeIn(GymMotion.standardTween()) togetherWith fadeOut(GymMotion.fastTween()) },
                    label = "exportBackupContent"
                ) { isExporting ->
                    Text(if (isExporting) "Exporting\u2026" else "Export Backup", fontWeight = FontWeight.Bold, color = Color(0xFF06121A), fontFamily = GymFonts.Display)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        FuturisticCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Upload, null, tint = GymColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Restore Records", color = GymColors.Text, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                text = "Choose Backup File",
                modifier = Modifier.height(44.dp),
                onClick = { openDoc.launch(arrayOf("application/zip", "application/json", "application/octet-stream")) }
            )
        }
        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(
                it,
                color = if (messageIsError) GymColors.Danger else GymColors.Success,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        Spacer(Modifier.height(14.dp))
        FuturisticCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Share, null, tint = GymColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share Backup File", color = GymColors.Text, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))

            if (latestBackup != null) {
                val f = latestBackup!!
                Column(modifier = Modifier.fillMaxWidth().clip(GymShapes.sm).background(GymColors.Surface2).padding(10.dp)) {
                    Text(f.name, color = GymColors.Text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "${formatBackupSize(f.length())} \u00B7 ${formatDate(f.lastModified())} \u00B7 ${formatTimeOfDay(f.lastModified())}",
                        color = GymColors.TextFaint, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            val shareInteractionSource = remember { MutableInteractionSource() }
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .gymPressScale(shareInteractionSource)
                    .clip(GymShapes.md)
                    .background(if (!sharing) GymColors.PrimaryGradient else Brush.linearGradient(listOf(GymColors.Surface3, GymColors.Surface3)))
                    .clickable(interactionSource = shareInteractionSource, indication = null, enabled = !sharing) {
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
                    }
            ) {
                // Section 16: idle -> working label swap communicated with a
                // short cross-fade rather than an instant text replace.
                AnimatedContent(
                    targetState = sharing,
                    transitionSpec = { fadeIn(GymMotion.standardTween()) togetherWith fadeOut(GymMotion.fastTween()) },
                    label = "shareBackupContent"
                ) { isSharing ->
                    Text(if (isSharing) "Preparing\u2026" else "Share Backup", fontWeight = FontWeight.Bold, color = Color(0xFF06121A), fontFamily = GymFonts.Display)
                }
            }

            shareMessage?.let {
                Text(it, color = GymColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        FuturisticCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp), onClick = { onNavigate(Screen.BackupHistory) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, null, tint = GymColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Backup History", color = GymColors.Text, fontWeight = FontWeight.Bold)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = GymColors.TextFaint)
            }
        }

    }
}

/**
 * Backup History (date/time-only, latest 3 months) - see
 * [com.majorgym.app.data.BackupHistoryPrefs]. Purely a read-only list of
 * when backups were taken; never shows or stores any actual backup content.
 */
@Composable
fun BackupHistoryScreen(vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    val entries = remember { vm.backupHistory() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = GymColors.Text,
                modifier = Modifier.clickable { onNavigate(Screen.Backup) }
            )
            Spacer(Modifier.width(12.dp))
            Text("BACKUP HISTORY", color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, letterSpacing = 0.5.sp, fontFamily = GymFonts.Display)
        }
        if (entries.isEmpty()) {
            GymEmptyState("No backups taken yet.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                items(entries) { millis ->
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.History, null, tint = GymColors.Accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(formatDate(millis), color = GymColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Text(formatTimeOfDay(millis), color = GymColors.TextMuted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
