package com.majorgym.app.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * All member data (including photos) lives entirely on-device:
 * - structured fields in a local SQLite database (Room)
 * - photos copied into the app's private internal storage as JPEG files
 * Nothing here ever touches the network, so the app works fully offline.
 */
class Repository(private val context: Context) {
    private val dao = AppDatabase.get(context).memberDao()

    fun observeAll() = dao.getAll()
    suspend fun allOnce() = dao.getAllOnce()
    suspend fun save(member: Member) = dao.upsert(member)

    /** True if some other member already has this phone number (spec section 1: unique phone). */
    suspend fun isPhoneTaken(phone: String, excludingId: String = ""): Boolean =
        dao.countByPhone(phone, excludingId) > 0
    suspend fun delete(member: Member) = dao.delete(member)

    /**
     * Deletes a member and everything tied to them: their database row, their
     * profile photo file, and their ID proof photo file. Fingerprint templates
     * live inside the database row itself (a BLOB column, not a separate file),
     * so deleting the row already takes care of that.
     *
     * Used by both the manual Delete action and [MembershipCleanupWorker] —
     * previously, deleting a member only removed the database row and silently
     * left orphaned photo files behind forever; this replaces that everywhere.
     */
    suspend fun deleteWithFiles(member: Member) {
        deletePhoto(member.id)
        deleteIdProofPhoto(member.id)
        dao.delete(member)
    }

    /** Removes a member's profile photo file, if any (safe no-op if there isn't one). */
    fun deletePhoto(memberId: String) {
        val f = File(File(context.filesDir, "photos"), "$memberId.jpg")
        if (f.exists()) f.delete()
    }

    suspend fun replaceAll(members: List<Member>) {
        dao.clearAll()
        dao.insertAll(members)
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
     */
    fun savePhoto(memberId: String, uri: Uri): String {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val dest = File(dir, "$memberId.jpg")
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

    /**
     * Saves an ID proof photo (Feature 3), downscaled and JPEG-compressed —
     * ID document photos don't need to be full camera resolution, and this
     * keeps a database of 100,000+ members from accumulating huge files.
     * Returns "" if the image couldn't be decoded, instead of throwing.
     */
    fun saveIdProofPhoto(memberId: String, uri: Uri): String {
        val dir = File(context.filesDir, "id_photos").apply { mkdirs() }
        val dest = File(dir, "$memberId.jpg")
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
        val f = File(File(context.filesDir, "id_photos"), "$memberId.jpg")
        if (f.exists()) f.delete()
    }

    suspend fun upsertAll(items: List<Member>) {
        items.forEach { dao.upsert(it) }
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
        if (toUpsert.isNotEmpty()) dao.insertAll(toUpsert)
    }

    // ---------- Share Backup File (Feature 1) ----------
    //
    // Now shares the newest ZIP this app has produced by ANY means (Backup
    // Now, Export Backup, or an automatic backup) rather than keeping its own
    // separate JSON copy - the owner should only ever see/share the ZIP.

    /** The newest backup this app has ever produced, from either the manual
     *  or automatic backup folder, or null if there isn't one yet. */
    fun latestInternalBackupFile(): File? =
        (listAutoBackups() + manualBackupsDir().listFiles { f -> f.extension == "zip" }.orEmpty())
            .maxByOrNull { it.lastModified() }

    /** Generates a fresh ZIP backup right now and stashes it in the manual
     *  backups folder - used when Share Backup is tapped and no backup exists
     *  yet at all (spec: never make the owner press Export Backup first). */
    suspend fun createBackupNow(): File =
        BackupService.createZipBackup(context, this, newManualBackupFile(shareTimestampLabel()))

    /** What Share Backup actually calls: the latest backup if one exists, otherwise
     *  generates one on the spot. */
    suspend fun getOrCreateLatestBackup(): File = latestInternalBackupFile() ?: createBackupNow()

    private fun shareTimestampLabel(): String =
        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))

    // ---------- Local ZIP backup system (Automatic / Backup Now / Export) ----------

    /** Where scheduled automatic backups live. Separate from [manualBackupsDir]
     *  so the 30-backup retention policy can only ever touch files it
     *  created - it never sees, and can never delete, anything from a manual
     *  "Backup Now" or an exported backup. */
    private fun autoBackupsDir(): File = File(context.filesDir, "backups/auto").apply { mkdirs() }

    /** Where "Backup Now" saves its local copy. Exempt from automatic
     *  retention per spec section 5/14 - only [autoBackupsDir] is pruned. */
    private fun manualBackupsDir(): File = File(context.filesDir, "backups/manual").apply { mkdirs() }

    /** A single overwritten-each-time pre-restore snapshot (section 9) - a
     *  recovery point on disk, separate from the in-memory rollback safety
     *  that already comes from [mergeAll]'s atomic DB write. Never shown to
     *  the owner and never counted toward any retention limit. */
    private fun safetyBackupFile(): File = File(context.filesDir, "backups/safety_backup.zip")

    fun listAutoBackups(): List<File> =
        autoBackupsDir().listFiles { f -> f.extension == "zip" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun newAutoBackupFile(timestampLabel: String): File =
        File(autoBackupsDir(), "MajorGym_AutoBackup_$timestampLabel.zip")

    fun newManualBackupFile(timestampLabel: String): File =
        File(manualBackupsDir(), "MajorGym_Backup_$timestampLabel.zip")

    /** Rolling retention (spec section 5): keeps only the newest [keep]
     *  automatic backups. Only ever called right after a new automatic backup
     *  has itself been saved and verified - see [BackupWorker] - so a failed
     *  backup attempt never reaches this and never costs a prior good backup. */
    fun pruneAutoBackups(keep: Int = 30) {
        listAutoBackups().drop(keep).forEach { it.delete() }
    }

    fun autoBackupStorageBytes(): Long = listAutoBackups().sumOf { it.length() }

    /** Writes a snapshot of the CURRENT (pre-restore) member data to
     *  [safetyBackupFile] using the exact same generator/compressor the real
     *  backups use, so it's just as restorable if it's ever needed by hand. */
    suspend fun writeSafetyBackupSnapshot() {
        val json = BackupManager.exportJson(context, dao.getAllOnce())
        BackupZip.write(json, safetyBackupFile())
    }
}
