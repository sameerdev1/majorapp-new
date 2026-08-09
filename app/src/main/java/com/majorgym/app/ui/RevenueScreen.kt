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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majorgym.app.Screen
import com.majorgym.app.data.Member
import com.majorgym.app.data.formatMoney
import com.majorgym.app.data.toHistoryList
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Month-wise revenue breakdown, reached by tapping the Dashboard's revenue card.
 *
 * Deliberately reads the same [members] list (and each member's existing
 * historyJson) that the Dashboard's TOTAL REVENUE total already sums — every
 * Joined/Renewed [com.majorgym.app.data.HistoryEntry] already carries its own
 * dateMillis, recorded at the moment that payment was saved. So this is not a
 * second, separate revenue store that could ever drift from the Dashboard
 * total: it's the exact same numbers, just grouped by month instead of
 * collapsed into one figure. No schema change, no migration, no new field.
 */
@Composable
fun RevenueScreen(members: List<Member>, onNavigate: (Screen) -> Unit) {
    val monthly = remember(members) { revenueByMonth(members) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 90.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = GymColors.Text,
                    modifier = Modifier.clickable { onNavigate(Screen.Dashboard) }
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "REVENUE",
                    color = GymColors.Text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            }
        }

        if (monthly.isEmpty()) {
            item {
                Text(
                    "No revenue recorded yet.",
                    color = GymColors.TextFaint,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }

        items(monthly, key = { it.first.toString() }) { (month, total) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GymColors.SurfaceCard)
                    .border(1.dp, GymColors.Border, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.year,
                    color = GymColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    formatMoney(total),
                    color = GymColors.Gold,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

/**
 * Groups every Joined/Renewed transaction across all members by the calendar
 * month its dateMillis falls in, latest month first. A month with zero
 * transactions simply doesn't appear — with no fixed date range to anchor a
 * "last 12 months, some at ₹0" view, an absent month already communicates
 * "no revenue" without cluttering a real gym's history with empty rows
 * stretching back to whenever the very first member joined.
 */
private fun revenueByMonth(members: List<Member>): List<Pair<YearMonth, Double>> {
    val byMonth = LinkedHashMap<YearMonth, Double>()
    members.forEach { member ->
        member.historyJson.toHistoryList().forEach { entry ->
            val month = YearMonth.from(
                Instant.ofEpochMilli(entry.dateMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            )
            byMonth[month] = (byMonth[month] ?: 0.0) + entry.fee
        }
    }
    return byMonth.entries.sortedByDescending { it.key }.map { it.key to it.value }
}
