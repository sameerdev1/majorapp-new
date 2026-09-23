package com.majorgym.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncChangeLogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: SyncChangeLogEntry): Long

    /** Returns the Room-generated row id for each entry, or -1 for one that
     *  was silently ignored because its [SyncChangeLogEntry.changeId] was
     *  already present - i.e. exactly the "was this actually new" signal
     *  [Repository.applyRemoteChanges] needs for idempotency (fix "receiving
     *  the same synchronization change more than once must not duplicate"). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<SyncChangeLogEntry>): List<Long>

    /** This device's own next sequence number for a given origin device is
     *  (highest [SyncChangeLogEntry.seq] already logged for it) + 1 - see
     *  [Repository.nextSeq]. */
    @Query("SELECT MAX(seq) FROM sync_change_log WHERE originDeviceId = :deviceId")
    suspend fun maxSeqFor(deviceId: String): Long?

    /** This device's full version vector: the highest seq it has ever seen
     *  from each origin device (its own changes AND everything it has
     *  learned via sync from anyone else - the "gossip" part). Sent to a
     *  peer so it knows exactly what to send back, and nothing more. */
    @Query("SELECT originDeviceId, MAX(seq) as maxSeq FROM sync_change_log GROUP BY originDeviceId")
    suspend fun versionVectorRaw(): List<DeviceSeq>

    @Query("SELECT * FROM sync_change_log")
    suspend fun getAllOnce(): List<SyncChangeLogEntry>

    /** Full change history for one record, oldest first - what
     *  [Repository.recomputeAndApplyMember] / `recomputeAndApplyAttendance`
     *  replay to derive that record's current merged state. Ordered by
     *  timestamp first (so changes are replayed in the order they actually
     *  happened) with (originDeviceId, seq) as a deterministic tiebreak for
     *  same-millisecond changes - the same tiebreak every device applies, so
     *  all devices land on the same answer (fix #3: "deterministic
     *  conflict-resolution rule... all synced devices must reach the same
     *  decision"). */
    @Query(
        "SELECT * FROM sync_change_log WHERE entityType = :entityType AND recordId = :recordId " +
            "ORDER BY timestampMillis ASC, originDeviceId ASC, seq ASC"
    )
    suspend fun getForRecord(entityType: String, recordId: String): List<SyncChangeLogEntry>

    /** Every Member row that has no OP_ADD change-log entry at all yet - i.e.
     *  a record that predates the change-log system (see
     *  [Repository.backfillPreSyncHistoryIfNeeded]) and would otherwise be
     *  invisible to [versionVectorRaw]/[Repository.changesMissingForPeer] and
     *  so never reach a peer that has never synced with this device before. */
    @Query(
        "SELECT m.id FROM members m WHERE NOT EXISTS (" +
            "SELECT 1 FROM sync_change_log s WHERE s.entityType = 'MEMBER' AND s.operation = 'ADD' AND s.recordId = m.id" +
            ")"
    )
    suspend fun memberIdsMissingAddHistory(): List<String>

    /** Same as [memberIdsMissingAddHistory], for attendance rows keyed by
     *  their stable [AttendanceRecord.globalId]. */
    @Query(
        "SELECT a.globalId FROM attendance_records a WHERE NOT EXISTS (" +
            "SELECT 1 FROM sync_change_log s WHERE s.entityType = 'ATTENDANCE' AND s.operation = 'ADD' AND s.recordId = a.globalId" +
            ")"
    )
    suspend fun attendanceIdsMissingAddHistory(): List<String>

    /** Same as [memberIdsMissingAddHistory]/[attendanceIdsMissingAddHistory],
     *  for 30-Day Expired Member Archive rows (Device Sync fix #1) keyed by
     *  [ArchivedMember.originalMemberId] - covers archive rows that already
     *  existed locally (created before this fix, or via a backup restore)
     *  and so would otherwise never reach a peer that hasn't synced with
     *  this device before. */
    @Query(
        "SELECT am.originalMemberId FROM archived_members am WHERE NOT EXISTS (" +
            "SELECT 1 FROM sync_change_log s WHERE s.entityType = 'ARCHIVED_MEMBER' AND s.operation = 'ADD' AND s.recordId = am.originalMemberId" +
            ")"
    )
    suspend fun archivedMemberIdsMissingAddHistory(): List<String>
}
