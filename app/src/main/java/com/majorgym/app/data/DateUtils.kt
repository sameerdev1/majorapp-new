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

fun formatMoney(v: Double): String {
    val nf = java.text.NumberFormat.getIntegerInstance(Locale("en", "IN"))
    return "\u20B9" + nf.format(v.toLong())
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
