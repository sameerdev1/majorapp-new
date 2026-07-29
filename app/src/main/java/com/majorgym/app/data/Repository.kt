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
}
