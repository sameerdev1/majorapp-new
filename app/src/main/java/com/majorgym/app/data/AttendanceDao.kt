package com.majorgym.app.data

import androidx.room.Dao
import androidx.room.Insert
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

    /** Recent history for one member, newest first - used by the
     *  tap-a-record attendance-history view. */
    @Query("SELECT * FROM attendance_records WHERE memberId = :memberId ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeForMember(memberId: String, limit: Int = 90): Flow<List<AttendanceRecord>>
}
