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
 *  by that version" rather than a hard failure.
 *
 *  v3: fingerprint templates are embedded as plain Base64
 *  ("fingerprintTemplateBase64") instead of being portable-encrypted with a
 *  Sync Code ("fingerprintTemplateProtected", v2 only) - see [exportJson]. A
 *  v2 backup's protected templates can no longer be decrypted (there's no key
 *  left to do it with) and are skipped gracefully on import; everything else
 *  in the record still restores normally.
 *
 *  v4: an optional top-level "attendance" array is added alongside "members"
 *  (Change 2) - see [exportJson]'s attendance parameter and [importAttendance].
 *  A backup with no attendance key (any older version, or a device-sync
 *  payload that never included one) simply has no attendance to restore;
 *  nothing about member restore is affected either way. */
const val BACKUP_SCHEMA_VERSION = 4

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
     * Fingerprint templates are written out as plain Base64
     * ("fingerprintTemplateBase64"), the same way they're already carried
     * across an already-encrypted LAN sync frame (see [SyncManager]) - not
     * portable-encrypted with a Sync Code. That layer used to make a manual
     * backup's fingerprints recoverable only on a device that still had the
     * same Sync Code, which meant an Export -> full data clear -> Import
     * round trip on the SAME phone could silently lose every fingerprint,
     * since clearing app data wipes the Sync Code too. A manual backup file
     * is already sensitive (it also carries member photos and password
     * hashes as plain/Base64 fields) and is the owner's own responsibility to
     * store safely, so this isn't a new category of exposure - it just stops
     * fingerprint restore from depending on unrelated app state.
     */
    /**
     * [attendance] is optional and defaults to empty, so every existing
     * caller (device sync, which must keep working exactly as it does today)
     * is completely unaffected. Only the local ZIP backup path (Change 2)
     * passes real attendance rows in.
     */
    fun exportJson(context: Context, members: List<Member>, attendance: List<AttendanceRecord> = emptyList()): String {
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

            // Fingerprint template - see fix #1/#7. [m.fingerprintTemplate]
            // arriving here is always already plaintext (Repository decrypts
            // on the way out of the database); it's written out as plain
            // Base64 so restoring this same backup - on this phone or a new
            // one - never depends on a Sync Code or any other app state that
            // a data clear/reinstall would wipe out. Re-encrypted at rest
            // with the restoring device's own Keystore key by
            // Repository.encryptedForStorage() the moment it's saved back
            // into the database (see BackupService/SyncManager import paths).
            if (m.fingerprintTemplate != null) {
                o.put("fingerprintTemplateBase64", Base64.encodeToString(m.fingerprintTemplate, Base64.NO_WRAP))
            }
            if (m.pendingDeletionMillis != null) o.put("pendingDeletionMillis", m.pendingDeletionMillis)
            // Hold Members feature: ACTIVE or HOLD - see Member.membershipState.
            o.put("membershipState", m.membershipState)
            arr.put(o)
        }

        // Change 2: attendance rows, kept as their own array rather than
        // nested under each member - a record's link back to its member is
        // the existing stable memberId, same as everywhere else in the app.
        val attendanceArr = JSONArray()
        attendance.forEach { rec ->
            attendanceArr.put(
                JSONObject().apply {
                    put("memberId", rec.memberId)
                    put("timestampMillis", rec.timestampMillis)
                    put("dayEpoch", rec.dayEpoch)
                    put("session", rec.session)
                }
            )
        }

        return JSONObject().apply {
            put("app", "MajorGym")
            put("schemaVersion", BACKUP_SCHEMA_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("members", arr)
            put("attendance", attendanceArr)
        }.toString()
    }

    /**
     * Reads the optional "attendance" array (Change 2, v4+). Missing key (any
     * older backup, or a device-sync payload) or a malformed individual row
     * just yields no/fewer attendance records - never a failure, and never
     * affects member restore, which is parsed and applied separately by
     * [importJson].
     */
    fun importAttendance(json: String): List<AttendanceRecord> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("attendance") ?: JSONArray()
        val result = mutableListOf<AttendanceRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val memberId = o.optString("memberId", "")
            if (memberId.isBlank()) continue
            val timestampMillis = if (o.has("timestampMillis")) o.optLong("timestampMillis") else continue
            val session = o.optString("session", "").ifBlank { sessionOf(timestampMillis).name }
            val dayEpoch = if (o.has("dayEpoch")) o.optLong("dayEpoch") else timestampMillis.toLocalDate().toMillis()
            result.add(
                AttendanceRecord(
                    memberId = memberId,
                    timestampMillis = timestampMillis,
                    dayEpoch = dayEpoch,
                    session = session
                )
            )
        }
        return result
    }

    /**
     * Reads `fingerprintTemplateBase64` (current format, and also what
     * [SyncManager] sends over the already-encrypted LAN sync channel) as a
     * plain template. A `fingerprintTemplateProtected` field (pre-v3 backups,
     * portable-encrypted with a Sync Code) can no longer be decrypted - that
     * key derivation no longer exists anywhere in this app - so it's skipped
     * with a log warning: the member still imports normally, just without a
     * fingerprint, rather than the whole restore failing or a new key/prompt
     * being invented to recover it.
     */
    fun importJson(context: Context, json: String): List<Member> {
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
            if (protectedB64.isNotBlank()) {
                // Pre-v3 backup: this was portable-encrypted with a Sync Code
                // whose derivation this app no longer implements at all - it
                // can never be decrypted again, by design (no key/password is
                // ever asked for). Not a crash; the member just needs
                // re-enrollment after this restore, same as any other
                // template that fails to come back.
                Log.w(TAG, "Backup contains a pre-v3 protected fingerprint template for $id - it can no longer be decrypted and will be skipped")
            } else {
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
                        pendingDeletionMillis = if (o.has("pendingDeletionMillis")) o.optLong("pendingDeletionMillis") else null,
                        membershipState = o.optString("membershipState", MembershipState.ACTIVE)
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
