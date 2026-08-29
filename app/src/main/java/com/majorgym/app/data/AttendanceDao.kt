package com.majorgym.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Insert
    suspend fun insert(record: AttendanceRecord)

    /** Scoped to a single calendar day (indexed on dayEpoch) so the
     *  Attendance Logs screen only ever loads that one day's rows, no
     *  matter how large the historical log grows (spec section 12:
     *  performance / large datasets). */
    @Query("SELECT * FROM attendance_records WHERE dayEpoch = :dayEpoch ORDER BY timestampMillis DESC")
    fun observeForDay(dayEpoch: Long): Flow<List<AttendanceRecord>>

    /** Full history for one member, newest first - used by the tap-a-record
     *  attendance-history view (Change 3: the complete available 4-month
     *  history, not just a recent slice - the 4-month retention cleanup is
     *  what keeps this bounded, not a row limit here). */
    @Query("SELECT * FROM attendance_records WHERE memberId = :memberId ORDER BY timestampMillis DESC")
    fun observeForMember(memberId: String): Flow<List<AttendanceRecord>>

    /** All currently-retained attendance rows - used to include attendance in
     *  a backup (Change 2). Bounded by the 4-month retention cleanup, not by
     *  this query, so it's always "whatever's currently kept". */
    @Query("SELECT * FROM attendance_records")
    suspend fun getAllOnce(): List<AttendanceRecord>

    /** Restores attendance rows from a backup without duplicating on a
     *  repeated restore - relies on the unique (memberId, timestampMillis)
     *  index to silently skip a row that's already present (Change 2).
     *  Callers must pass records with id=0 so ids are assigned fresh locally
     *  rather than trusting another device's autoincrement values. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringDuplicates(records: List<AttendanceRecord>)

    /** Change 1: rolling retention - deletes everything older than the given
     *  calendar-day cutoff. Indexed on dayEpoch, so this is a simple indexed
     *  range delete regardless of table size. */
    @Query("DELETE FROM attendance_records WHERE dayEpoch < :cutoffDayEpoch")
    suspend fun deleteOlderThan(cutoffDayEpoch: Long)

    /** Change 4/5: removes every attendance row for one member - called from
     *  the single shared deletion path (Repository.deleteWithFiles) used by
     *  both manual delete and the existing automatic member-deletion worker,
     *  so a member is never left with orphaned attendance records. */
    @Query("DELETE FROM attendance_records WHERE memberId = :memberId")
    suspend fun deleteForMember(memberId: String)
}
