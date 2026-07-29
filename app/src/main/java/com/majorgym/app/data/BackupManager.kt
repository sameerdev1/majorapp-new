package com.majorgym.app.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exports/imports the full gym database - including member photos, embedded as
 * base64 - into a single self-contained JSON file the user can save anywhere
 * (Google Drive, SD card, email) and restore from on a new phone or after a
 * reinstall.
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
            val photoBase64 = m.photoPath?.let { path ->
                val f = File(path)
                if (f.exists()) Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) else null
            }
            if (photoBase64 != null) o.put("photoBase64", photoBase64)
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
                    historyJson = o.getJSONArray("history").toString()
                )
            )
        }
        return result
    }
}
