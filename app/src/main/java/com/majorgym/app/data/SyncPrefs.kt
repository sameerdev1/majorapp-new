package com.majorgym.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PairedDevice(val id: String, val name: String, val lastSyncedMillis: Long)

/**
 * Stores this device's sync identity, the shared "sync code" for this gym's
 * device circle, and the list of devices it has successfully synced with.
 * Any number of devices may join the circle - the only requirements are
 * having the app, the exact matching sync code, and passing the existing
 * authentication check (see SyncManager).
 *
 * Nothing here is ever transmitted in the clear: only a SHA-256 hash of the
 * sync code is ever sent over the network (see SyncManager), so the code
 * itself never leaves the device.
 */
class SyncPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("majorgym_sync", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null) ?: (Build.MODEL ?: "This phone")
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var syncCode: String?
        get() = prefs.getString(KEY_SYNC_CODE, null)
        set(value) = prefs.edit().putString(KEY_SYNC_CODE, value).apply()

    fun pairedDevices(): List<PairedDevice> {
        val raw = prefs.getString(KEY_PAIRED, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PairedDevice(o.getString("id"), o.getString("name"), o.optLong("last", 0L))
        }
    }

    /** No device-count cap: any device with a matching sync code may join
     *  the circle (the actual gate is the code-hash check in SyncManager,
     *  which this function does not affect). Kept as a function rather than
     *  removed outright so SyncManager's call site doesn't need to change. */
    fun canAdd(id: String): Boolean = true

    fun recordSync(id: String, name: String) {
        val updated = pairedDevices().filter { it.id != id } + PairedDevice(id, name, System.currentTimeMillis())
        val arr = JSONArray()
        updated.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("last", it.lastSyncedMillis)
            })
        }
        prefs.edit().putString(KEY_PAIRED, arr.toString()).apply()
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SYNC_CODE = "sync_code"
        private const val KEY_PAIRED = "paired_devices"
    }
}
