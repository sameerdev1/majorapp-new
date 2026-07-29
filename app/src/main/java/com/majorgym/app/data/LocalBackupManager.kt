package com.majorgym.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Local-only backup/sync format.
 *
 * The app exports a portable JSON file containing member records.
 * The file can be transferred to another phone using USB, WhatsApp,
 * Telegram, Google Drive, Bluetooth, etc., and imported there.
 *
 * This intentionally avoids Firebase and any cloud database.
 */
class LocalBackupManager(
    private val context: Context,
    private val repository: Repository
) {
    suspend fun exportMembers(uri: Uri) = withContext(Dispatchers.IO) {
        val members = repository.allOnce()
        val root = JSONObject()
        root.put("format", "MajorGym Local Sync")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val array = JSONArray()
        members.forEach { member ->
            array.put(JSONObject().apply {
                put("id", member.id)
                put("name", member.name)
                put("phone", member.phone)
                put("photoPath", member.photoPath ?: JSONObject.NULL)
                put("plan", member.plan)
                put("fee", member.fee)
                put("joinedMillis", member.joinedMillis)
                put("expiryMillis", member.expiryMillis)
                put("historyJson", member.historyJson)
            })
        }
        root.put("members", array)

        context.contentResolver.openOutputStream(uri)?.use { output ->
            OutputStreamWriter(output).use { writer ->
                writer.write(root.toString())
            }
        } ?: error("Unable to open backup file")
    }

    suspend fun importMembers(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val json = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: error("Unable to open backup file")

        val root = JSONObject(json)
        require(root.optString("format") == "MajorGym Local Sync") {
            "This is not a valid MajorGym backup file."
        }

        val array = root.optJSONArray("members") ?: JSONArray()
        val imported = mutableListOf<Member>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            imported += Member(
                id = item.getString("id"),
                name = item.getString("name"),
                phone = item.optString("phone", ""),
                photoPath = if (item.isNull("photoPath")) null else item.optString("photoPath"),
                plan = item.optString("plan", ""),
                fee = item.optDouble("fee", 0.0),
                joinedMillis = item.optLong("joinedMillis", 0L),
                expiryMillis = item.optLong("expiryMillis", 0L),
                historyJson = item.optString("historyJson", "[]")
            )
        }

        // Import is ID-based. Existing IDs are updated; new IDs are inserted.
        repository.upsertAll(imported)
        ImportResult(imported.size)
    }

    data class ImportResult(val recordsProcessed: Int)
}
