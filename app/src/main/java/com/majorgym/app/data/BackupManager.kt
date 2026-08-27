package com.majorgym.app.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exports/imports the full gym database - including member photos, embedded
 * as base64 - into a single self-contained JSON document. This is the "JSON =
 * actual backup data" layer; [BackupZip]/[BackupService] wrap it into the
 * ZIP file the owner actually sees, so it can be restored on a new phone or
 * after a reinstall entirely offline, with no cloud service involved.
 */
object BackupManager {

    fun exportJson(context: Context, members: List<Member>): String {
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
            // Fingerprint template (a few hundred bytes), not an image — safe/small to embed.
            if (m.fingerprintTemplate != null) {
                o.put("fingerprintTemplateBase64", Base64.encodeToString(m.fingerprintTemplate, Base64.NO_WRAP))
            }
            // Feature 4 safeguard state — carried through so a synced/restored
            // device doesn't lose track of an in-progress pending deletion (or,
            // worst case if it did, the member simply gets re-flagged fresh on
            // the next daily check — never an unsafe outcome either way).
            if (m.pendingDeletionMillis != null) o.put("pendingDeletionMillis", m.pendingDeletionMillis)
            arr.put(o)
        }
        return JSONObject().apply {
            put("app", "MajorGym")
            put("exportedAt", System.currentTimeMillis())
            put("members", arr)
        }.toString()
    }

    fun importJson(context: Context, json: String): List<Member> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("members") ?: JSONArray()
        val photosDir = File(context.filesDir, "photos").apply { mkdirs() }
        val idPhotosDir = File(context.filesDir, "id_photos").apply { mkdirs() }
        val result = mutableListOf<Member>()

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            var photoPath: String? = null
            val b64 = o.optString("photoBase64", "")
            if (b64.isNotBlank()) {
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                val file = File(photosDir, "$id.jpg")
                file.writeBytes(bytes)
                photoPath = file.absolutePath
            }
            // Old backups never had this field — optString's default of "" makes
            // that read the same as "no ID proof provided", never a crash.
            var idProofPhotoPath = ""
            val idB64 = o.optString("idProofPhotoBase64", "")
            if (idB64.isNotBlank()) {
                val bytes = Base64.decode(idB64, Base64.NO_WRAP)
                val file = File(idPhotosDir, "$id.jpg")
                file.writeBytes(bytes)
                idProofPhotoPath = file.absolutePath
            }
            result.add(
                Member(
                    id = id,
                    name = o.getString("name"),
                    phone = o.getString("phone"),
                    photoPath = photoPath,
                    plan = o.getString("plan"),
                    fee = o.getDouble("fee"),
                    joinedMillis = o.getLong("joinedMillis"),
                    expiryMillis = o.getLong("expiryMillis"),
                    updatedAtMillis = o.optLong("updatedAtMillis", 0L),
                    historyJson = o.getJSONArray("history").toString(),
                    idProof = o.optString("idProof", ""),
                    idProofPhotoPath = idProofPhotoPath,
                    fingerprintTemplate = o.optString("fingerprintTemplateBase64", "")
                        .takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.NO_WRAP) },
                    pendingDeletionMillis = if (o.has("pendingDeletionMillis")) o.optLong("pendingDeletionMillis") else null
                )
            )
        }
        return result
    }
}
