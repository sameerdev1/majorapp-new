package com.majorgym.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.ZoneId

/**
 * One real, permanent check-in event.
 *
 * This is additive, not a replacement: [Member.lastAttendanceMillis] still
 * exists, is still written exactly where it always was, and still means
 * exactly what it always meant ("most recent check-in"), overwritten every
 * time. That field alone can't power a Logs screen because history is lost
 * the moment a member checks in again - there is nothing to show for
 * yesterday once today happens. This table exists purely so a full
 * per-day / per-member attendance history can actually be displayed; it
 * changes nothing about how, when, or why attendance gets recorded.
 */
@Entity(
    tableName = "attendance_records",
    indices = [Index("memberId"), Index("dayEpoch")]
)
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memberId: String,
    val timestampMillis: Long,
    /** Local calendar day (local midnight, epoch millis) this visit belongs
     *  to - computed once at write time so day lookups are a plain indexed
     *  equality check instead of per-row time-zone math at query time. */
    val dayEpoch: Long,
    /** [AttendanceSession.name] - stored as text rather than an ordinal so
     *  existing rows keep reading correctly even if the enum's declaration
     *  order ever changes later. */
    val session: String
)

enum class AttendanceSession { MORNING, EVENING }

/** Gym convention used for the Morning/Evening split: anything before noon
 *  local time counts as the Morning batch, noon onward is Evening. */
fun sessionOf(millis: Long): AttendanceSession {
    val hour = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).hour
    return if (hour < 12) AttendanceSession.MORNING else AttendanceSession.EVENING
}
