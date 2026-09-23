package com.majorgym.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majorgym.app.MembersViewModel
import com.majorgym.app.Screen
import com.majorgym.app.data.ArchivedMember
import com.majorgym.app.data.formatDate
import com.majorgym.app.data.formatMoney

/**
 * Section 10: Expired Archive screen - lists members the 30-Day Expired
 * Member Archive has moved out of the operational Members table. Simple and
 * lightweight, matching [FilteredMembersScreen]'s search pattern: search by
 * name or phone (section 10), tap a row to open its detail.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ExpiredArchiveScreen(archivedMembers: List<ArchivedMember>, onNavigate: (Screen) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = if (query.isBlank()) archivedMembers else archivedMembers.filter {
        it.name.contains(query, ignoreCase = true) || it.phone.contains(query)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = GymColors.Text,
                modifier = Modifier.clickable { onNavigate(Screen.Dashboard) }
            )
            Spacer(Modifier.width(12.dp))
            GymScreenTitle("Expired Archive")
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search by name or phone", color = GymColors.TextFaint) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = GymColors.Accent) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = gymFieldColors()
        )
        Spacer(Modifier.height(14.dp))

        if (shown.isEmpty()) {
            val message = if (query.isNotBlank()) "No archived members match your search." else "No expired members archived yet."
            Text(message, color = GymColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                items(shown, key = { it.originalMemberId }) { a ->
                    ArchivedMemberRow(a, Modifier.animateItemPlacement()) {
                        onNavigate(Screen.ArchivedMemberDetail(a.originalMemberId))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedMemberRow(a: ArchivedMember, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(GymShapes.lg)
            .background(GymColors.CardGlassGradient)
            .border(1.dp, GymColors.Border, GymShapes.lg)
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(GymColors.Accent.copy(alpha = 0.15f))
                .padding(10.dp)
        ) {
            Icon(Icons.Filled.Archive, contentDescription = null, tint = GymColors.Accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = GymColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(a.phone, color = GymColors.TextMuted, fontSize = 12.sp)
            Text("Archived ${formatDate(a.archivedAtMillis)}", color = GymColors.TextFaint, fontSize = 11.sp)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = GymColors.TextFaint)
    }
}

/**
 * Section 11: Restore / Renew. Shows the archived member's last known
 * membership info and archived date, plus a single action that restores
 * them into the normal operational Members table and hands off to the
 * existing Renew screen - no attendance history, photo, fingerprint, or QR
 * is brought back (none of that was preserved at archive time).
 */
@Composable
fun ArchivedMemberDetailScreen(archivedMemberId: String, vm: MembersViewModel, onNavigate: (Screen) -> Unit) {
    val archivedMembers by vm.archivedMembers.collectAsState()
    val a = archivedMembers.find { it.originalMemberId == archivedMemberId }

    if (a == null) {
        // Already restored (or the archive row otherwise disappeared) while
        // this screen was open - bounce back rather than show a blank/stale
        // detail view.
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GymColors.Text,
                    modifier = Modifier.clickable { onNavigate(Screen.ExpiredArchive) }
                )
                Spacer(Modifier.width(12.dp))
                GymScreenTitle("Expired Archive")
            }
            Text("This archived member is no longer available.", color = GymColors.TextFaint, fontSize = 13.sp)
        }
        return
    }

    var restoring by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = GymColors.Text,
                modifier = Modifier.clickable { onNavigate(Screen.ExpiredArchive) }
            )
            Spacer(Modifier.width(10.dp))
            GymHeaderText("ARCHIVED MEMBER", fontSize = 20.sp)
        }

        FuturisticCard(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Column {
                Text(a.name, color = GymColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                GymSectionLabel("Last membership")
                Spacer(Modifier.height(8.dp))
                ProfileRow(Icons.Filled.Call, "Phone", a.phone)
                ProfileRow(Icons.Filled.CalendarToday, "Joined", formatDate(a.joinedMillis))
                ProfileRow(Icons.Filled.FitnessCenter, "Last Plan", a.lastPlan)
                ProfileRow(Icons.Filled.CurrencyRupee, "Last Fee", formatMoney(a.lastFee))
                ProfileRow(Icons.Filled.EventAvailable, "Last Start Date", formatDate(a.lastStartMillis))
                ProfileRow(Icons.Filled.EventBusy, "Last Expiry Date", formatDate(a.lastExpiryMillis))
                ProfileRow(Icons.Filled.Archive, "Archived Date", formatDate(a.archivedAtMillis), last = true)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(GymShapes.md).background(GymColors.TextFaint.copy(alpha = 0.10f)).border(1.dp, GymColors.Border, GymShapes.md).padding(14.dp)
        ) {
            Icon(Icons.Filled.Info, null, tint = GymColors.TextMuted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Restoring brings this member back as an expired member with no old attendance history - renew them normally afterward.",
                color = GymColors.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f)
            )
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = GymColors.Danger, fontSize = 12.sp)
        }

        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (restoring) "Restoring..." else "Restore / Renew",
            icon = Icons.Filled.Restore,
            onClick = onClick@{
                if (restoring) return@onClick
                restoring = true
                error = null
                vm.restoreArchivedMember(a) { restored, err ->
                    restoring = false
                    if (restored != null) {
                        onNavigate(Screen.Renew(restored.id))
                    } else {
                        error = err
                    }
                }
            }
        )
    }
}
