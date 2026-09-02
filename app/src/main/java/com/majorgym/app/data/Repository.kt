package com.majorgym.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

private const val TAG = "Repository"

/** Change 1: how many months of attendance history are kept. Shared by
 *  [Repository.cleanupOldAttendance] (the daily retention delete) and used
 *  as the default so callers never need to restate the number. */
const val ATTENDANCE_RETENTION_MONTHS = 4L

/**
 * All member data (including photos) lives entirely on-device:
 * - structured fields in a local SQLite database (Room)
 * - photos copied into the app's private internal storage as JPEG files
 * Nothing here ever touches the network, so the app works fully offline.
 *
 * Fingerprint templates are encrypted at rest (fix #7): everywhere outside
 * this class, a [Member.fingerprintTemplate] is always plaintext ISO 19794-2
 * bytes ready to hand to [FingerprintScanner.match] - this class alone is
 * responsible for encrypting on the way into Room and decrypting on the way
 * out, via [CryptoUtils]. A template that fails to decrypt (e.g. Keystore key
 * gone after a factory reset) surfaces as null - "not enrolled" - rather than
 * crashing, since a stale unusable template is operationally identical to no
 * template at all and the owner can simply re-enroll.
 */
class Repository(private val context: Context) {
    /** Backup History (date/time-only log, see [BackupHistoryPrefs]'s own doc). */
    val backupHistory = BackupHistoryPrefs(context)
    private val dao = AppDatabase.get(context).memberDao()
    private val attendanceDao = AppDatabase.get(context).attendanceDao()
    private val changeLogDao = AppDatabase.get(context).syncChangeLogDao()
    /** Only used to read this device's own stable id (see [SyncPrefs.deviceId])
     *  when writing change-log entries - completely independent of whatever
     *  SyncManager/MembersViewModel's own SyncPrefs instance is doing;
     *  SharedPreferences-backed, so reading it here is always in sync. */
    private val syncPrefs = SyncPrefs(context)
    /** Serializes every change-log write so two saves racing on the same
     *  device can never be assigned the same [SyncChangeLogEntry.seq] (fix
     *  #3's "unique change/event ID" + "version or sequence information"
     *  depend on seq actually being unique per device). */
    private val changeLogMutex = Mutex()

    fun observeAll() = dao.getAll().map { list -> list.map { it.decryptedForApp() } }
    suspend fun allOnce() = dao.getAllOnce().map { it.decryptedForApp() }

    /**
     * Saves a Member and records exactly what changed as a Device Sync
     * change-log entry (fix #3) - a full ADD snapshot for a brand-new member,
     * or only the fields that actually differ from what's currently stored
     * for an edit/renewal/attendance-timestamp-bump/etc. This is the single
     * save path every screen and ViewModel already calls, so every kind of
     * Member mutation the app supports is covered automatically without
     * needing to touch each call site.
     */
    suspend fun save(member: Member) = changeLogMutex.withLock {
        val existingEncrypted = dao.getByIdOnce(member.id)
        val existing = existingEncrypted?.decryptedForApp()
        val now = System.currentTimeMillis()
        val toStore = if (member.updatedAtMillis > 0) member else member.copy(updatedAtMillis = now)
        dao.upsert(toStore.encryptedForStorage())

        val deviceId = syncPrefs.deviceId
        if (existing == null) {
            changeLogDao.insert(
                SyncChangeLogEntry(
                    changeId = UUID.randomUUID().toString(),
                    entityType = ENTITY_MEMBER,
                    recordId = toStore.id,
                    operation = OP_ADD,
                    originDeviceId = deviceId,
                    seq = nextSeq(deviceId),
                    timestampMillis = toStore.updatedAtMillis,
                    fieldsJson = SyncChangeCodec.encodeMember(toStore).toString()
                )
            )
        } else {
            val changedKeys = SyncChangeCodec.diffKeys(existing, toStore)
            if (changedKeys.isNotEmpty()) {
                changeLogDao.insert(
                    SyncChangeLogEntry(
                        changeId = UUID.randomUUID().toString(),
                        entityType = ENTITY_MEMBER,
                        recordId = toStore.id,
                        operation = OP_UPDATE,
                        originDeviceId = deviceId,
                        seq = nextSeq(deviceId),
                        timestampMillis = toStore.updatedAtMillis,
                        fieldsJson = SyncChangeCodec.encodeMember(toStore, changedKeys).toString()
                    )
                )
            } else {
                Unit
            }
        }
    }

    /** This device's next sequence number for [deviceId] - always called
     *  from inside [changeLogMutex] so it's race-free. */
    private suspend fun nextSeq(deviceId: String): Long = (changeLogDao.maxSeqFor(deviceId) ?: 0L) + 1

    private suspend fun logMemberDeleted(id: String) {
        val deviceId = syncPrefs.deviceId
        changeLogDao.insert(
            SyncChangeLogEntry(
                changeId = UUID.randomUUID().toString(),
                entityType = ENTITY_MEMBER,
                recordId = id,
                operation = OP_DELETE,
                originDeviceId = deviceId,
                seq = nextSeq(deviceId),
                timestampMillis = System.currentTimeMillis(),
                fieldsJson = null
            )
        )
    }

    // ---------- Device Sync (fixes #1-#3) ----------
    //
    // Replicates the change log itself (add/update/delete events, each with
    // a stable id, origin device, and sequence number) instead of comparing
    // whole Member snapshots - see SyncChangeLogEntry's doc for the full
    // reasoning. SyncManager calls these three methods and nothing else.

    /** The highest [SyncChangeLogEntry.seq] this device has ever recorded or
     *  learned about, per origin device - what this device tells a sync peer
     *  it already has, so the peer only sends what's actually missing. */
    suspend fun localVersionVector(): Map<String, Long> =
        changeLogDao.versionVectorRaw().associate { it.originDeviceId to it.maxSeq }

    /** Every locally-known change a peer with [peerVector] doesn't have yet -
     *  regardless of which device originally made it (fix #3's "eventual
     *  consistency... not dependent on one master device": this device
     *  forwards changes it only learned about secondhand from a third
     *  device, too). */
    suspend fun changesMissingForPeer(peerVector: Map<String, Long>): List<SyncChangeLogEntry> =
        changeLogDao.getAllOnce().filter { it.seq > (peerVector[it.originDeviceId] ?: 0L) }

    /**
     * Applies a batch of change-log entries received from a sync peer:
     * stores each into the local log (a duplicate [SyncChangeLogEntry.changeId]
     * is silently ignored - the idempotency the spec requires), then
     * recomputes and re-applies the merged state of every record any new
     * entry touched. Returns how many entries were genuinely new (for the
     * "records synced" count shown to the owner).
     */
    suspend fun applyRemoteChanges(entries: List<SyncChangeLogEntry>): Int = changeLogMutex.withLock {
        if (entries.isEmpty()) return@withLock 0
        val rowIds = changeLogDao.insertAll(entries)
        val newlyInserted = entries.filterIndexed { i, _ -> rowIds.getOrElse(i) { -1L } != -1L }
        val affectedMembers = newlyInserted.filter { it.entityType == ENTITY_MEMBER }.map { it.recordId }.toSet()
        val affectedAttendance = newlyInserted.filter { it.entityType == ENTITY_ATTENDANCE }.map { it.recordId }.toSet()
        affectedMembers.forEach { recomputeAndApplyMember(it) }
        affectedAttendance.forEach { recomputeAndApplyAttendance(it) }
        newlyInserted.size
    }

    /**
     * Derives record [recordId]'s current correct state by replaying its
     * ENTIRE known change history (oldest first) and applies that to the
     * local database - never trusts a single incoming change in isolation.
     * A DELETE anywhere in the history wins permanently: once every device
     * agrees a member was deleted, no update (older or newer, already known
     * or arriving later from a device that was offline) can bring them back
     * (fix #3: "delete vs update... a deleted Member must not reappear").
     * Otherwise, starting from the ADD snapshot, each UPDATE's changed
     * fields are applied in order - so the field that was changed *last*
     * (by real timestamp, with originDeviceId/seq as a tiebreak every device
     * resolves identically) is what wins for that field specifically, while
     * a different field changed elsewhere is untouched (fix #3: "preserve
     * BOTH changes"). If this device doesn't have the ADD entry yet (it
     * arrived via a device that hasn't relayed it yet), nothing is applied
     * this round - the UPDATE(s) already sit safely in the log and will be
     * replayed correctly the moment the ADD does arrive.
     */
    private suspend fun recomputeAndApplyMember(recordId: String) {
        val entries = changeLogDao.getForRecord(ENTITY_MEMBER, recordId)
        if (entries.isEmpty()) return
        if (entries.any { it.operation == OP_DELETE }) {
            if (dao.getByIdOnce(recordId) != null) {
                deletePhoto(recordId)
                deleteIdProofPhoto(recordId)
                dao.deleteById(recordId)
            }
            return
        }
        val addEntry = entries.firstOrNull { it.operation == OP_ADD } ?: return
        val merged = JSONObject(addEntry.fieldsJson ?: "{}")
        entries.filter { it.operation == OP_UPDATE }.forEach { u ->
            u.fieldsJson?.let { js ->
                val changed = JSONObject(js)
                changed.keys().forEach { k -> merged.put(k, changed.get(k)) }
            }
        }
        val latestTimestamp = entries.maxOf { it.timestampMillis }
        val member = SyncChangeCodec.decodeMemberFields(context, recordId, merged, latestTimestamp) ?: return
        try {
            dao.upsert(member.encryptedForStorage())
        } catch (e: Exception) {
            // e.g. a genuine phone-number collision between two members
            // independently added on different devices - a pre-existing
            // data-entry conflict for the owner to resolve by hand, not
            // something a merge should crash the whole sync over.
            Log.w(TAG, "Could not apply synced member $recordId: ${e.message}")
        }
    }

    /** Attendance visits are add-only (no edits/deletes travel through sync -
     *  see spec scope), so this just needs the one ADD entry for [globalId].
     *  [AttendanceDao.insertIgnoringDuplicate]'s unique indices are the
     *  actual "never duplicate no matter how many times sync runs" guard. */
    private suspend fun recomputeAndApplyAttendance(globalId: String) {
        val addEntry = changeLogDao.getForRecord(ENTITY_ATTENDANCE, globalId).firstOrNull { it.operation == OP_ADD } ?: return
        val f = JSONObject(addEntry.fieldsJson ?: return)
        val memberId = f.optString("memberId", "")
        if (memberId.isBlank()) return
        val timestampMillis = f.optLong("timestampMillis", addEntry.timestampMillis)
        val dayEpoch = if (f.has("dayEpoch")) f.optLong("dayEpoch") else timestampMillis.toLocalDate().toMillis()
        val session = f.optString("session", "").ifBlank { sessionOf(timestampMillis).name }
        attendanceDao.insertIgnoringDuplicate(
            AttendanceRecord(memberId = memberId, timestampMillis = timestampMillis, dayEpoch = dayEpoch, session = session, globalId = globalId)
        )
    }

    // ---------- Attendance Logs (new feature) ----------
    //
    // Purely additive: appends one row per check-in to [attendanceDao] on top
    // of whatever the caller already does with Member.lastAttendanceMillis.
    // Never replaces, reads, or overwrites that field.

    /** One indexed day's worth of check-ins only - see [AttendanceDao.observeForDay]. */
    fun observeAttendanceForDay(dayEpochMillis: Long) = attendanceDao.observeForDay(dayEpochMillis)

    /** Complete available check-in history for one member, newest first
     *  (Change 3) - bounded by the 4-month retention cleanup, not a limit
     *  here. */
    fun observeAttendanceForMember(memberId: String) = attendanceDao.observeForMember(memberId)

    /** All currently-retained attendance rows - used to fold attendance into
     *  a backup (Change 2). */
    suspend fun attendanceAllOnce(): List<AttendanceRecord> = attendanceDao.getAllOnce()

    /** Merges attendance rows read from a restored backup. Ids are stripped
     *  (set to 0, i.e. "assign fresh") before insert since they're per-device
     *  autoincrement values, not stable identifiers across devices; the
     *  unique (memberId, timestampMillis) index is what actually prevents a
     *  record from being duplicated, including across repeated restores of
     *  the same backup. */
    suspend fun restoreAttendance(records: List<AttendanceRecord>) {
        if (records.isEmpty()) return
        attendanceDao.insertAllIgnoringDuplicates(records.map { it.copy(id = 0) })
    }

    /** Change 1: deletes attendance older than [retentionMonths] (default 4).
     *  Called daily by AttendanceRetentionWorker; never touches attendance
     *  recording, QR/fingerprint check-in logic, or Morning/Evening grouping -
     *  it only removes rows that already fall outside the retention window. */
    suspend fun cleanupOldAttendance(retentionMonths: Long = ATTENDANCE_RETENTION_MONTHS) {
        val cutoffDayEpoch = addMonthsMillis(System.currentTimeMillis(), -retentionMonths)
        attendanceDao.deleteOlderThan(cutoffDayEpoch)
    }

    /** Appends one new attendance-visit row. Safe to call as many times as a
     *  member actually checks in that day - each call is a new row, nothing
     *  is overwritten. Also records this visit in the Device Sync change log
     *  (fix #2) so it reaches every other synced device, exactly once each,
     *  even if a device was offline when it happened. */
    suspend fun recordAttendanceVisit(memberId: String, atMillis: Long = System.currentTimeMillis()) = changeLogMutex.withLock {
        val day = atMillis.toLocalDate().toMillis()
        val session = sessionOf(atMillis).name
        val globalId = UUID.randomUUID().toString()
        attendanceDao.insert(
            AttendanceRecord(
                memberId = memberId,
                timestampMillis = atMillis,
                dayEpoch = day,
                session = session,
                globalId = globalId
            )
        )
        val deviceId = syncPrefs.deviceId
        val fields = JSONObject().apply {
            put("memberId", memberId)
            put("timestampMillis", atMillis)
            put("dayEpoch", day)
            put("session", session)
        }
        changeLogDao.insert(
            SyncChangeLogEntry(
                changeId = UUID.randomUUID().toString(),
                entityType = ENTITY_ATTENDANCE,
                recordId = globalId,
                operation = OP_ADD,
                originDeviceId = deviceId,
                seq = nextSeq(deviceId),
                timestampMillis = atMillis,
                fieldsJson = fields.toString()
            )
        )
    }

    private fun Member.decryptedForApp(): Member {
        val plain = fingerprintTemplate?.let {
            CryptoUtils.decryptAtRestOrLegacy(it) ?: run {
                Log.w(TAG, "Fingerprint template for $id could not be decrypted - treating as not enrolled")
                null
            }
        }
        return if (plain === fingerprintTemplate) this else copy(fingerprintTemplate = plain)
    }

    private fun Member.encryptedForStorage(): Member {
        val encrypted = fingerprintTemplate?.let { CryptoUtils.encryptAtRest(it) }
        return if (encrypted === fingerprintTemplate) this else copy(fingerprintTemplate = encrypted)
    }

    /** True if some other member already has this phone number (spec section 1: unique phone). */
    suspend fun isPhoneTaken(phone: String, excludingId: String = ""): Boolean =
        dao.countByPhone(phone, excludingId) > 0
    suspend fun delete(member: Member) = changeLogMutex.withLock {
        dao.delete(member)
        logMemberDeleted(member.id)
    }

    /**
     * Deletes a member and everything tied to them: their database row, their
     * profile photo file, their ID proof photo file, and (Changes 4 & 5) all
     * of their attendance records. Fingerprint templates live inside the
     * database row itself (a BLOB column, not a separate file), so deleting
     * the row already takes care of that.
     *
     * Used by the manual Delete action (Profile -> Delete) — previously,
     * deleting a member only removed the database row and silently left
     * orphaned photo files behind forever; this fixes that. [MembershipHoldWorker]
     * no longer deletes members at all (Hold Members feature): a long-expired,
     * never-renewed member is moved to [MembershipState.HOLD] via the normal
     * [save] path instead, with nothing removed.
     */
    suspend fun deleteWithFiles(member: Member) = changeLogMutex.withLock {
        deletePhoto(member.id)
        deleteIdProofPhoto(member.id)
        dao.delete(member)
        attendanceDao.deleteForMember(member.id)
        // Device Sync fix #1: a real, permanent tombstone - the whole reason
        // a deletion can't just be inferred from "this id is no longer in
        // the members table" (a device that never even knew about this
        // member wouldn't be able to tell "never existed" from "existed and
        // was deleted" apart otherwise).
        logMemberDeleted(member.id)
    }

    /** Removes a member's profile photo file, if any (safe no-op if there isn't one). */
    fun deletePhoto(memberId: String) {
        val f = safePhotoFile(File(context.filesDir, "photos"), memberId) ?: return
        if (f.exists()) f.delete()
    }

    suspend fun replaceAll(members: List<Member>) {
        dao.clearAll()
        dao.insertAll(members.map { it.encryptedForStorage() })
    }

    /**
     * Copies the picked photo into permanent internal app storage — downscaled
     * and JPEG-compressed the same way [saveIdProofPhoto] already was (Feature 2
     * of the performance pass). Camera/gallery photos can be several MB each
     * uncompressed; this keeps per-member storage small without a visible
     * quality drop in the app's circular avatars or full-screen photo viewer.
     * Always compresses fresh from the freshly-picked [uri], never re-compresses
     * an already-saved file, so repeated edits can't progressively degrade it.
     * Returns "" if the image couldn't be decoded, instead of throwing.
     *
     * Runs on [Dispatchers.IO] (fix #6): decoding/scaling/compressing a
     * full-resolution camera image is expensive and was previously called
     * straight from a Compose UI callback on the main thread, which could
     * visibly freeze the app on a large photo. Callers now suspend from a
     * coroutine (see MembersViewModel/Screens.kt) instead of blocking the UI.
     */
    suspend fun savePhoto(memberId: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val dest = safePhotoFile(dir, memberId) ?: return@withContext ""
        compressInto(uri, dest)
    }

    /**
     * Saves an ID proof photo (Feature 3), downscaled and JPEG-compressed —
     * ID document photos don't need to be full camera resolution, and this
     * keeps a database of 100,000+ members from accumulating huge files.
     * Returns "" if the image couldn't be decoded, instead of throwing.
     * Runs on [Dispatchers.IO] - see [savePhoto].
     */
    suspend fun saveIdProofPhoto(memberId: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "id_photos").apply { mkdirs() }
        val dest = safePhotoFile(dir, memberId) ?: return@withContext ""
        compressInto(uri, dest)
    }

    private fun compressInto(uri: Uri, dest: File): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val original = android.graphics.BitmapFactory.decodeStream(input) ?: return ""
            val maxDimension = 1600
            val longSide = maxOf(original.width, original.height)
            val scale = if (longSide > maxDimension) maxDimension.toFloat() / longSide else 1f
            val bitmap = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    original, (original.width * scale).toInt(), (original.height * scale).toInt(), true
                )
            } else original
            dest.outputStream().use { output ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)
            }
            if (bitmap !== original) original.recycle()
        } ?: return ""
        return dest.absolutePath
    }

    /** Removes a member's ID proof photo file, if any (safe no-op if there isn't one). */
    fun deleteIdProofPhoto(memberId: String) {
        val f = safePhotoFile(File(context.filesDir, "id_photos"), memberId) ?: return
        if (f.exists()) f.delete()
    }

    /** Same path-traversal defense as backup/sync import (fix #1), applied
     *  consistently here too even though [memberId] is normally an
     *  app-generated UUID - defense in depth costs nothing and means this
     *  helper is safe to reuse verbatim if a future caller ever feeds it an
     *  externally-supplied id. */
    private fun safePhotoFile(dir: File, memberId: String): File? =
        try {
            FileSafety.resolveWithin(dir, memberId, "jpg")
        } catch (e: FileSafety.UnsafePathException) {
            Log.w(TAG, "Rejected unsafe photo path for id='$memberId': ${e.message}")
            null
        }

    suspend fun upsertAll(items: List<Member>) {
        items.forEach { save(it) }
    }

    /**
     * Merges an incoming list of members (from a synced device or a backup
     * file) into local storage without deleting anything: for each incoming
     * record, keep whichever copy - existing or incoming - was edited most
     * recently (by [Member.updatedAtMillis]). Unlike [replaceAll], this never
     * wipes local-only records that aren't present in [incoming].
     */
    suspend fun mergeAll(incoming: List<Member>) {
        val current = dao.getAllOnce().associateBy { it.id }
        val toUpsert = incoming.filter { inc ->
            val existing = current[inc.id]
            existing == null || inc.updatedAtMillis >= existing.updatedAtMillis
        }
        if (toUpsert.isNotEmpty()) dao.insertAll(toUpsert.map { it.encryptedForStorage() })
    }

    // ---------- Share Backup File (Feature 1) ----------
    //
    // Shares the newest ZIP this app has produced by any manual means (Backup
    // Now or Export Backup) rather than keeping its own separate JSON copy -
    // the owner should only ever see/share the ZIP.

    /** The newest manual backup this app has ever produced, or null if there
     *  isn't one yet. */
    fun latestInternalBackupFile(): File? =
        manualBackupsDir().listFiles { f -> f.extension == "zip" }?.maxByOrNull { it.lastModified() }

    /** Generates a fresh ZIP backup right now and stashes it in the manual
     *  backups folder - used when Share Backup is tapped and no backup exists
     *  yet at all (spec: never make the owner press Export Backup first). */
    suspend fun createBackupNow(): File =
        BackupService.createZipBackup(context, this, newManualBackupFile(shareTimestampLabel())).also {
            backupHistory.recordBackupTaken()
        }

    /** What Share Backup actually calls: the latest backup if one exists, otherwise
     *  generates one on the spot. */
    suspend fun getOrCreateLatestBackup(): File = latestInternalBackupFile() ?: createBackupNow()

    private fun shareTimestampLabel(): String =
        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))

    // ---------- Local ZIP backup system (Backup Now / Export) ----------

    /** Where "Backup Now" and Export Backup save their local copy. */
    private fun manualBackupsDir(): File = File(context.filesDir, "backups/manual").apply { mkdirs() }

    /** A single overwritten-each-time pre-restore snapshot (section 9) - a
     *  recovery point on disk, separate from the in-memory rollback safety
     *  that already comes from [mergeAll]'s atomic DB write. Never shown to
     *  the owner and never counted toward any retention limit. */
    private fun safetyBackupFile(): File = File(context.filesDir, "backups/safety_backup.zip")

    fun newManualBackupFile(timestampLabel: String): File =
        File(manualBackupsDir(), "MajorGym_Backup_$timestampLabel.zip")

    /** Writes a snapshot of the CURRENT (pre-restore) member data to
     *  [safetyBackupFile] using the exact same generator/compressor the real
     *  backups use, so it's just as restorable if it's ever needed by hand. */
    suspend fun writeSafetyBackupSnapshot() {
        val json = BackupManager.exportJson(context, allOnce(), attendanceAllOnce())
        BackupZip.write(json, safetyBackupFile())
    }
}
