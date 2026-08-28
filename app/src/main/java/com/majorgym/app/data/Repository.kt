package com.majorgym.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Repository"

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
    private val dao = AppDatabase.get(context).memberDao()

    fun observeAll() = dao.getAll().map { list -> list.map { it.decryptedForApp() } }
    suspend fun allOnce() = dao.getAllOnce().map { it.decryptedForApp() }
    suspend fun save(member: Member) = dao.upsert(member.encryptedForStorage())

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
        BackupService.createZipBackup(context, this, newManualBackupFile(shareTimestampLabel()))

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
        val json = BackupManager.exportJson(context, allOnce())
        BackupZip.write(json, safetyBackupFile())
    }
}
