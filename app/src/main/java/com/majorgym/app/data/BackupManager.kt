package com.majorgym.app.data

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "BackupManager"

/** Current backup JSON schema version. Bump this whenever a field is added or
 *  a stored representation changes. Old backups (missing "schemaVersion", or
 *  with a lower one) still restore - every field below is read defensively
 *  with a safe fallback, so "missing field" just means "wasn't captured yet
 *  by that version" rather than a hard failure. */
const val BACKUP_SCHEMA_VERSION = 2

/**
 * Exports/imports the full gym database - including member photos, embedded
 * as base64 - into a single self-contained JSON document. This is the "JSON =
 * actual backup data" layer; [BackupZip]/[BackupService] wrap it into the
 * ZIP file the owner actually sees, so it can be restored on a new phone or
 * after a reinstall entirely offline, with no cloud service involved.
 *
 * Every value that ends up as part of a filename (currently just each
 * member's [Member.id]) is routed through [FileSafety.resolveWithin], which
 * rejects/neutralizes "../", absolute paths, and anything else that isn't a
 * plain safe token - a malicious backup file can never make this app write
 * outside its own photos/id_photos directories.
 */
object BackupManager {

    /**
     * @param syncCode if non-null, [Member.fingerprintTemplate] is embedded
     *   encrypted with a key derived from it ([CryptoUtils.encryptPortable]) -
     *   recoverable only by a device that has been given the same Sync Code.
     *   If null (no Sync Code has ever been set on this device), fingerprint
     *   templates are omitted from the export entirely rather than ever being
     *   written out as recoverable plaintext/Base64 - members simply need
     *   re-enrollment after a restore in that case, which is disclosed to the
     *   owner by [BackupService].
     */
    fun exportJson(context: Context, members: List<Member>, syncCode: String? = null): String {
        val arr = JSONArray()
        members.forEach { m ->
            val o = JSONObject()
            o.put("id", m.id)
            o.put("name", m.name)
            o.put("phone", m.phone)
            o.put("plan", m.plan)
            o.put("fee", m.fee)
            o.put("joinedMillis", m.joinedMillis)
            o.put("expiryMillis", m.expiryMillis)
            o.put("updatedAtMillis", m.updatedAtMillis)
            o.put("history", JSONArray(m.historyJson))
            o.put("idProof", m.idProof)
            // Fields previously missing from backups entirely - see fix #3.
            o.put("passwordHash", m.passwordHash)
            o.put("createdAtMillis", m.createdAtMillis)
            if (m.lastAttendanceMillis != null) o.put("lastAttendanceMillis", m.lastAttendanceMillis)
            o.put("archived", m.archived)
            o.put("qrToken", m.qrToken)
            o.put("qrTokenExpiryMillis", m.qrTokenExpiryMillis)

            val idPhotoBase64 = m.idProofPhotoPath.takeIf { it.isNotBlank() }?.let { path ->
                val f = File(path)
                if (f.exists()) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null
            }
            if (idPhotoBase64 != null) o.put("idProofPhotoBase64", idPhotoBase64)
            val photoBase64 = m.photoPath?.let { path ->
                val f = File(path)
                if (f.exists()) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null
            }
            if (photoBase64 != null) o.put("photoBase64", photoBase64)

            // Fingerprint template - see fix #7/#8. Never written as raw
            // Base64 here: either portable-encrypted with the Sync Code, or
            // left out entirely. [m.fingerprintTemplate] arriving here is
            // always already plaintext (Repository decrypts on the way out
            // of the database), which is what makes it safe/correct to
            // re-protect it for this specific export destination.
            if (m.fingerprintTemplate != null) {
                if (syncCode != null) {
                    val protectedBytes = CryptoUtils.encryptPortable(m.fingerprintTemplate, syncCode)
                    o.put("fingerprintTemplateProtected", Base64.encodeToString(protectedBytes, Base64.NO_WRAP))
                } else {
                    Log.w(TAG, "No Sync Code set - omitting fingerprint template from backup for member ${m.id}")
                }
            }
            if (m.pendingDeletionMillis != null) o.put("pendingDeletionMillis", m.pendingDeletionMillis)
            arr.put(o)
        }
        return JSONObject().apply {
            put("app", "MajorGym")
            put("schemaVersion", BACKUP_SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("members", arr)
        }.toString()
    }

    /**
     * @param syncCode used to decrypt any `fingerprintTemplateProtected`
     *   entries - must be the same code the export used, or those templates
     *   come back as null (member imports fine, just without a fingerprint;
     *   never crashes the whole restore over one field).
     */
    fun importJson(context: Context, json: String, syncCode: String? = null): List<Member> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("members") ?: JSONArray()
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        val idPhotosDir = File(context.filesDir, "id_photos").apply { mkdirs() }
        val result = mutableListOf<Member>()

        for (i in 0 until arr.length()) {
            val o = try {
                arr.getJSONObject(i)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping backup record $i - not a JSON object")
                continue
            }
            val id = try {
                o.getString("id")
            } catch (e: Exception) {
                Log.w(TAG, "Skipping backup record $i - missing/invalid id")
                continue
            }

            var photoPath: String? = null
            val b64 = o.optString("photoBase64", "")
            if (b64.isNotBlank()) {
                try {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    val file = FileSafety.resolveWithin(photosDir, id, "jpg")
                    file.writeBytes(bytes)
                    photoPath = file.absolutePath
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping photo for record $id: ${e.message}")
                }
            }
            // Old backups never had this field - optString's default of "" makes
            // that read the same as "no ID proof provided", never a crash.
            var idProofPhotoPath = ""
            val idB64 = o.optString("idProofPhotoBase64", "")
            if (idB64.isNotBlank()) {
                try {
                    val bytes = Base64.decode(idB64, Base64.NO_WRAP)
                    val file = FileSafety.resolveWithin(idPhotosDir, id, "jpg")
                    file.writeBytes(bytes)
                    idProofPhotoPath = file.absolutePath
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping ID proof photo for record $id: ${e.message}")
                }
            }

            var fingerprintTemplate: ByteArray? = null
            val protectedB64 = o.optString("fingerprintTemplateProtected", "")
            if (protectedB64.isNotBlank() && syncCode != null) {
                val protectedBytes = runCatching { Base64.decode(protectedB64, Base64.NO_WRAP) }.getOrNull()
                fingerprintTemplate = protectedBytes?.let { CryptoUtils.decryptPortable(it, syncCode) }
            } else if (protectedB64.isNotBlank()) {
                Log.w(TAG, "Backup contains a protected fingerprint template but no Sync Code is set - skipping it for $id")
            } else {
                // Legacy (pre-#8) backups embedded the template as plain
                // Base64 under this old key. Still readable so old backups
                // aren't silently broken, but never written out that way again.
                fingerprintTemplate = o.optString("fingerprintTemplateBase64", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            }

            try {
                result.add(
                    Member(
                        id = id,
                        name = o.getString("name"),
                        phone = o.optString("phone", ""),
                        photoPath = photoPath,
                        plan = o.optString("plan", ""),
                        fee = o.optDouble("fee", 0.0),
                        joinedMillis = o.optLong("joinedMillis", 0L),
                        expiryMillis = o.optLong("expiryMillis", 0L),
                        updatedAtMillis = o.optLong("updatedAtMillis", 0L),
                        historyJson = safeHistoryArray(o),
                        idProof = o.optString("idProof", ""),
                        idProofPhotoPath = idProofPhotoPath,
                        passwordHash = o.optString("passwordHash", ""),
                        createdAtMillis = o.optLong("createdAtMillis", o.optLong("joinedMillis", 0L)),
                        lastAttendanceMillis = if (o.has("lastAttendanceMillis")) o.optLong("lastAttendanceMillis") else null,
                        archived = o.optBoolean("archived", false),
                        qrToken = o.optString("qrToken", ""),
                        qrTokenExpiryMillis = o.optLong("qrTokenExpiryMillis", 0L),
                        fingerprintTemplate = fingerprintTemplate,
                        pendingDeletionMillis = if (o.has("pendingDeletionMillis")) o.optLong("pendingDeletionMillis") else null
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed backup record $id: ${e.message}")
            }
        }
        return result
    }

    /** Never lets one corrupted history array fail the whole member record -
     *  falls back to an empty history list, per fix #5. */
    private fun safeHistoryArray(o: JSONObject): String = try {
        o.optJSONArray("history")?.toString() ?: "[]"
    } catch (e: Exception) {
        "[]"
    }
}
