package com.majorgym.app.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Encodes/decodes the `fieldsJson` payload carried by a [SyncChangeLogEntry],
 * and the wire format used to actually exchange changes/version vectors
 * during Device Sync (see [SyncManager]).
 *
 * A Member's photo, ID-proof photo, and fingerprint template are represented
 * here by their actual bytes (Base64), not by [Member.photoPath] /
 * [Member.idProofPhotoPath] - those are local absolute file paths that mean
 * nothing on another device. The receiving side ([decodeMemberFields])
 * writes the bytes to its own photos/id_photos directory and computes its
 * own local path, the same way [BackupManager.importJson] already does for
 * backup restore.
 */
object SyncChangeCodec {
    private const val K_NAME = "name"
    private const val K_PHONE = "phone"
    private const val K_PLAN = "plan"
    private const val K_FEE = "fee"
    private const val K_JOINED = "joinedMillis"
    private const val K_EXPIRY = "expiryMillis"
    private const val K_HISTORY = "history"
    private const val K_PASSWORD_HASH = "passwordHash"
    private const val K_CREATED = "createdAtMillis"
    private const val K_LAST_ATTENDANCE = "lastAttendanceMillis"
    private const val K_ARCHIVED = "archived"
    private const val K_QR_TOKEN = "qrToken"
    private const val K_QR_EXPIRY = "qrTokenExpiryMillis"
    private const val K_ID_PROOF = "idProof"
    private const val K_PENDING_DELETION = "pendingDeletionMillis"
    private const val K_MEMBERSHIP_STATE = "membershipState"
    private const val K_PHOTO = "photoBase64"
    private const val K_ID_PHOTO = "idProofPhotoBase64"
    private const val K_FINGERPRINT = "fingerprintTemplateBase64"

    /** All field keys a full "ADD" snapshot carries. */
    private val ALL_KEYS = setOf(
        K_NAME, K_PHONE, K_PLAN, K_FEE, K_JOINED, K_EXPIRY, K_HISTORY, K_PASSWORD_HASH,
        K_CREATED, K_LAST_ATTENDANCE, K_ARCHIVED, K_QR_TOKEN, K_QR_EXPIRY, K_ID_PROOF,
        K_PENDING_DELETION, K_MEMBERSHIP_STATE, K_PHOTO, K_ID_PHOTO, K_FINGERPRINT
    )

    /** Encodes [m] into a change-log field payload. Pass [keys] = null for a
     *  full ADD snapshot (every field), or a subset for an UPDATE (only the
     *  fields that actually changed - see [diffKeys]). [m] is expected to
     *  already carry a *plaintext* fingerprint template (i.e. called before
     *  Repository's at-rest encryption), same convention as everywhere else
     *  outside Repository. */
    fun encodeMember(m: Member, keys: Set<String>? = null): JSONObject {
        val want = keys ?: ALL_KEYS
        val o = JSONObject()
        if (K_NAME in want) o.put(K_NAME, m.name)
        if (K_PHONE in want) o.put(K_PHONE, m.phone)
        if (K_PLAN in want) o.put(K_PLAN, m.plan)
        if (K_FEE in want) o.put(K_FEE, m.fee)
        if (K_JOINED in want) o.put(K_JOINED, m.joinedMillis)
        if (K_EXPIRY in want) o.put(K_EXPIRY, m.expiryMillis)
        if (K_HISTORY in want) o.put(K_HISTORY, safeHistory(m.historyJson))
        if (K_PASSWORD_HASH in want) o.put(K_PASSWORD_HASH, m.passwordHash)
        if (K_CREATED in want) o.put(K_CREATED, m.createdAtMillis)
        if (K_LAST_ATTENDANCE in want && m.lastAttendanceMillis != null) o.put(K_LAST_ATTENDANCE, m.lastAttendanceMillis)
        if (K_ARCHIVED in want) o.put(K_ARCHIVED, m.archived)
        if (K_QR_TOKEN in want) o.put(K_QR_TOKEN, m.qrToken)
        if (K_QR_EXPIRY in want) o.put(K_QR_EXPIRY, m.qrTokenExpiryMillis)
        if (K_ID_PROOF in want) o.put(K_ID_PROOF, m.idProof)
        if (K_PENDING_DELETION in want && m.pendingDeletionMillis != null) o.put(K_PENDING_DELETION, m.pendingDeletionMillis)
        if (K_MEMBERSHIP_STATE in want) o.put(K_MEMBERSHIP_STATE, m.membershipState)
        if (K_PHOTO in want) o.put(K_PHOTO, readFileBase64(m.photoPath))
        if (K_ID_PHOTO in want) o.put(K_ID_PHOTO, readFileBase64(m.idProofPhotoPath.ifBlank { null }))
        if (K_FINGERPRINT in want) o.put(K_FINGERPRINT, m.fingerprintTemplate?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
        return o
    }

    /** Which fields actually changed between the currently-stored [existing]
     *  record and the [updated] one about to be saved - i.e. what an UPDATE
     *  change log entry needs to carry (fix #3: "preserve BOTH changes" when
     *  two devices edit different fields). Both are expected decrypted
     *  (plaintext fingerprint template). Photo/ID-photo changes are detected
     *  by the file's last-modified time against [existing]'s
     *  [Member.updatedAtMillis], since [Member.photoPath] itself is a stable
     *  per-member path that doesn't change when the same photo slot is
     *  re-uploaded. */
    fun diffKeys(existing: Member, updated: Member): Set<String> {
        val keys = mutableSetOf<String>()
        if (existing.name != updated.name) keys += K_NAME
        if (existing.phone != updated.phone) keys += K_PHONE
        if (existing.plan != updated.plan) keys += K_PLAN
        if (existing.fee != updated.fee) keys += K_FEE
        if (existing.joinedMillis != updated.joinedMillis) keys += K_JOINED
        if (existing.expiryMillis != updated.expiryMillis) keys += K_EXPIRY
        if (existing.historyJson != updated.historyJson) keys += K_HISTORY
        if (existing.passwordHash != updated.passwordHash) keys += K_PASSWORD_HASH
        if (existing.createdAtMillis != updated.createdAtMillis) keys += K_CREATED
        if (existing.lastAttendanceMillis != updated.lastAttendanceMillis) keys += K_LAST_ATTENDANCE
        if (existing.archived != updated.archived) keys += K_ARCHIVED
        if (existing.qrToken != updated.qrToken) keys += K_QR_TOKEN
        if (existing.qrTokenExpiryMillis != updated.qrTokenExpiryMillis) keys += K_QR_EXPIRY
        if (existing.idProof != updated.idProof) keys += K_ID_PROOF
        if (existing.pendingDeletionMillis != updated.pendingDeletionMillis) keys += K_PENDING_DELETION
        if (existing.membershipState != updated.membershipState) keys += K_MEMBERSHIP_STATE
        val fpChanged = when {
            existing.fingerprintTemplate == null && updated.fingerprintTemplate == null -> false
            existing.fingerprintTemplate == null || updated.fingerprintTemplate == null -> true
            else -> !existing.fingerprintTemplate.contentEquals(updated.fingerprintTemplate)
        }
        if (fpChanged) keys += K_FINGERPRINT
        if (pathContentChanged(existing.photoPath, updated.photoPath, existing.updatedAtMillis)) keys += K_PHOTO
        if (pathContentChanged(existing.idProofPhotoPath.ifBlank { null }, updated.idProofPhotoPath.ifBlank { null }, existing.updatedAtMillis)) keys += K_ID_PHOTO
        return keys
    }

    /** Rebuilds a [Member] from a merged (ADD snapshot + replayed UPDATEs)
     *  field map - see [Repository.recomputeAndApplyMember]. Writes any
     *  embedded photo/ID-photo/fingerprint bytes to this device's own
     *  storage. Returns null only if the payload is too malformed to use
     *  (missing even a name), so one bad record can't break sync for
     *  everything else. */
    fun decodeMemberFields(context: Context, recordId: String, fields: JSONObject, timestampMillis: Long): Member? {
        if (!fields.has(K_NAME)) return null
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        val idPhotosDir = File(context.filesDir, "id_photos").apply { mkdirs() }

        var photoPath: String? = null
        val photoB64 = fields.optString(K_PHOTO, "")
        if (photoB64.isNotBlank()) {
            try {
                val f = FileSafety.resolveWithin(photosDir, recordId, "jpg")
                f.writeBytes(Base64.decode(photoB64, Base64.NO_WRAP))
                photoPath = f.absolutePath
            } catch (_: Exception) { /* skip this photo, rest of the record still applies */ }
        }
        var idProofPhotoPath = ""
        val idPhotoB64 = fields.optString(K_ID_PHOTO, "")
        if (idPhotoB64.isNotBlank()) {
            try {
                val f = FileSafety.resolveWithin(idPhotosDir, recordId, "jpg")
                f.writeBytes(Base64.decode(idPhotoB64, Base64.NO_WRAP))
                idProofPhotoPath = f.absolutePath
            } catch (_: Exception) { }
        }
        val fingerprintTemplate = fields.optString(K_FINGERPRINT, "").takeIf { it.isNotBlank() }
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

        return Member(
            id = recordId,
            name = fields.optString(K_NAME, ""),
            phone = fields.optString(K_PHONE, ""),
            photoPath = photoPath,
            plan = fields.optString(K_PLAN, ""),
            fee = fields.optDouble(K_FEE, 0.0),
            joinedMillis = fields.optLong(K_JOINED, 0L),
            expiryMillis = fields.optLong(K_EXPIRY, 0L),
            historyJson = fields.optJSONArray(K_HISTORY)?.toString() ?: "[]",
            updatedAtMillis = timestampMillis,
            passwordHash = fields.optString(K_PASSWORD_HASH, ""),
            createdAtMillis = fields.optLong(K_CREATED, 0L),
            lastAttendanceMillis = if (fields.has(K_LAST_ATTENDANCE)) fields.optLong(K_LAST_ATTENDANCE) else null,
            archived = fields.optBoolean(K_ARCHIVED, false),
            qrToken = fields.optString(K_QR_TOKEN, ""),
            qrTokenExpiryMillis = fields.optLong(K_QR_EXPIRY, 0L),
            idProof = fields.optString(K_ID_PROOF, ""),
            idProofPhotoPath = idProofPhotoPath,
            fingerprintTemplate = fingerprintTemplate,
            pendingDeletionMillis = if (fields.has(K_PENDING_DELETION)) fields.optLong(K_PENDING_DELETION) else null,
            membershipState = fields.optString(K_MEMBERSHIP_STATE, MembershipState.ACTIVE)
        )
    }

    // ---------- Wire format (version vectors + change batches) ----------

    fun encodeVersionVector(v: Map<String, Long>): JSONObject =
        JSONObject().apply { v.forEach { (k, value) -> put(k, value) } }

    fun decodeVersionVector(o: JSONObject): Map<String, Long> {
        val m = mutableMapOf<String, Long>()
        o.keys().forEach { k -> m[k] = o.optLong(k, 0L) }
        return m
    }

    fun encodeChangeLog(entries: List<SyncChangeLogEntry>): JSONArray {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("changeId", e.changeId)
                    put("entityType", e.entityType)
                    put("recordId", e.recordId)
                    put("operation", e.operation)
                    put("originDeviceId", e.originDeviceId)
                    put("seq", e.seq)
                    put("timestampMillis", e.timestampMillis)
                    if (e.fieldsJson != null) put("fields", JSONObject(e.fieldsJson))
                }
            )
        }
        return arr
    }

    /** Never throws on a malformed individual entry - it's just skipped, the
     *  same "one bad record can't break the whole exchange" principle as
     *  everywhere else in this app's import paths. */
    fun decodeChangeLog(arr: JSONArray): List<SyncChangeLogEntry> {
        val out = mutableListOf<SyncChangeLogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try {
                out.add(
                    SyncChangeLogEntry(
                        changeId = o.getString("changeId"),
                        entityType = o.getString("entityType"),
                        recordId = o.getString("recordId"),
                        operation = o.getString("operation"),
                        originDeviceId = o.getString("originDeviceId"),
                        seq = o.getLong("seq"),
                        timestampMillis = o.optLong("timestampMillis", 0L),
                        fieldsJson = o.optJSONObject("fields")?.toString()
                    )
                )
            } catch (_: Exception) { /* skip malformed entry */ }
        }
        return out
    }

    private fun safeHistory(historyJson: String): JSONArray = try {
        JSONArray(historyJson.ifBlank { "[]" })
    } catch (e: Exception) {
        JSONArray()
    }

    private fun readFileBase64(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val f = File(path)
        if (!f.exists()) return ""
        return try { Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) } catch (e: Exception) { "" }
    }

    /** True if [newPath]'s file content looks like it changed since
     *  [sinceMillis] (i.e. was written during the edit that's being saved
     *  right now), or if the photo was added/removed outright. */
    private fun pathContentChanged(oldPath: String?, newPath: String?, sinceMillis: Long): Boolean {
        if (oldPath.isNullOrBlank() && newPath.isNullOrBlank()) return false
        if (oldPath.isNullOrBlank() != newPath.isNullOrBlank()) return true
        val f = File(newPath!!)
        return f.exists() && f.lastModified() >= sinceMillis
    }
}
