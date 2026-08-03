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
    suspend fun replaceAll(members: List<Member>) {
        dao.clearAll()
        dao.insertAll(members)
    }

    /** Copies the picked photo into permanent internal app storage and returns its path. */
    fun savePhoto(memberId: String, uri: Uri): String {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        val dest = File(dir, "$memberId.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
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

    /**
     * Android's Export Backup button uses the system file picker (SAF), so the
     * app has no reliable way to later "find" wherever the user chose to save
     * it — modern Android doesn't allow searching the filesystem freely. So
     * Share Backup works against its own app-private folder instead: every
     * backup (from Export Backup, or generated here on the spot) also gets a
     * timestamped copy saved here, which this folder can always enumerate.
     */
    private fun internalBackupsDir(): File = File(context.filesDir, "backups").apply { mkdirs() }

    /** The newest backup this app has ever produced, or null if there isn't one yet. */
    fun latestInternalBackupFile(): File? =
        internalBackupsDir().listFiles { f -> f.extension == "json" }?.maxByOrNull { it.lastModified() }

    /** Stashes an already-generated backup JSON (e.g. right after "Export Backup"
     *  succeeds) so Share Backup can find it later. */
    fun saveInternalBackupCopy(json: String): File {
        val file = File(internalBackupsDir(), "backup_${System.currentTimeMillis()}.json")
        file.writeText(json)
        return file
    }

    /** Generates a fresh backup right now and stashes it — used when Share Backup
     *  is tapped and no backup exists yet at all (spec: never make the owner
     *  press Export Backup first). */
    suspend fun createBackupNow(): File {
        val json = BackupManager.exportJson(context, dao.getAllOnce())
        return saveInternalBackupCopy(json)
    }

    /** What Share Backup actually calls: the latest backup if one exists, otherwise
     *  generates one on the spot. */
    suspend fun getOrCreateLatestBackup(): File = latestInternalBackupFile() ?: createBackupNow()
}
