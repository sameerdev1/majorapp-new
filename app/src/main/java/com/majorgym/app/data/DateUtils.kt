package com.majorgym.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

fun LocalDate.toMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun addMonthsMillis(baseMillis: Long, months: Long): Long =
    baseMillis.toLocalDate().plusMonths(months).toMillis()

fun daysBetweenNow(targetMillis: Long): Long {
    val today = LocalDate.now()
    return ChronoUnit.DAYS.between(today, targetMillis.toLocalDate())
}

fun formatDate(millis: Long): String {
    val d = millis.toLocalDate()
    val month = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    return "%02d %s %d".format(d.dayOfMonth, month, d.year)
}

/** Same as [formatDate] but with a time component — used for the QR token expiry,
 *  where "today" isn't precise enough since the token expires at a specific hour. */
fun formatDateTime(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val d = zoned.toLocalDate()
    val month = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    val hour24 = zoned.hour
    val amPm = if (hour24 < 12) "AM" else "PM"
    val hour12 = when (val h = hour24 % 12) { 0 -> 12; else -> h }
    return "%02d %s %d, %d:%02d %s".format(d.dayOfMonth, month, d.year, hour12, zoned.minute, amPm)
}

fun formatMoney(v: Double): String {
    val nf = java.text.NumberFormat.getIntegerInstance(Locale("en", "IN"))
    return "\u20B9" + nf.format(v.toLong())
}

/** Time-only companion to [formatDate], used for the Share Backup File card
 *  (spec wants Backup Date and Backup Time shown as separate items). */
fun formatTimeOfDay(millis: Long): String {
    val zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val hour24 = zoned.hour
    val amPm = if (hour24 < 12) "AM" else "PM"
    val hour12 = when (val h = hour24 % 12) { 0 -> 12; else -> h }
    return "%d:%02d %s".format(hour12, zoned.minute, amPm)
}

/** Human-readable file size for the Share Backup File card. */
fun formatBackupSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

enum class MemberStatus { ACTIVE, EXPIRING, EXPIRED }

fun statusOf(expiryMillis: Long): MemberStatus {
    val days = daysBetweenNow(expiryMillis)
    return when {
        days < 0 -> MemberStatus.EXPIRED
        days <= 7 -> MemberStatus.EXPIRING
        else -> MemberStatus.ACTIVE
    }
}

val PLAN_MONTHS = linkedMapOf(
    "1 Month" to 1L,
    "3 Months" to 3L,
    "6 Months" to 6L,
    "12 Months" to 12L
)
