package com.majorgym.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majorgym.app.Screen
import com.majorgym.app.data.Member

/**
 * Shared body for all four Dashboard stat-card destinations (Total/Active/
 * Expiring/Expired Members). [members] must already be filtered by the
 * caller using the exact same statusOf(...) logic the Dashboard uses to
 * compute that card's number — this composable does no filtering of its
 * own beyond the optional in-list search box, so the list shown here can
 * never disagree with the count that was tapped to get here.
 *
 * Reuses [MemberRow] (the same row used by the bottom-nav Members tab), so
 * tapping a member or its Renew shortcut behaves identically everywhere.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun FilteredMembersScreen(
    title: String,
    members: List<Member>,
    showSearch: Boolean,
    emptyText: String,
    onNavigate: (Screen) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val shown = if (showSearch) {
        members.filter {
            it.name.contains(query, ignoreCase = true) || it.phone.contains(query) || it.idProof.contains(query, ignoreCase = true)
        }
    } else members

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back",
                tint = GymColors.Text,
                modifier = Modifier.clickable { onNavigate(Screen.Dashboard) }
            )
            Spacer(Modifier.width(12.dp))
            Text(title.uppercase(), color = GymColors.Text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = 0.5.sp)
        }

        if (showSearch) {
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
        }

        if (shown.isEmpty()) {
            val message = if (showSearch && query.isNotBlank()) "No members match your search." else emptyText
            Text(message, color = GymColors.TextFaint, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                items(shown, key = { it.id }) { m ->
                    MemberRow(m, onNavigate, Modifier.animateItemPlacement())
                }
            }
        }
    }
}
