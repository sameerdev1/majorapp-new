package com.majorgym.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class DriveBackupStatus { NONE, SUCCESS, FAILED, OFFLINE_PENDING, AUTH_EXPIRED }

data class DriveBackupHistoryEntry(
    val timestampMillis: Long,
    val sizeBytes: Long,
    val status: DriveBackupStatus,
    val fileName: String,
    val driveFileId: String?,
    val errorMessage: String? = null
)

/**
 * Local settings/state for the new Google Drive automatic backup feature.
 * Entirely separate from the existing manual Export/Restore/Share Backup
 * feature (BackupManager, LocalBackupManager untouched) — this only stores
 * non-secret configuration and status metadata: which account is connected
 * (by email, for display only), the schedule, retention, and a local log of
 * past backup attempts for the Backup History UI.
 *
 * No OAuth access/refresh token or password is ever stored here or anywhere
 * else in the app. Google Sign-In + GoogleAccountCredential (see
 * DriveBackupManager) pull a short-lived access token on demand from
 * Android's own AccountManager/Play Services — this app never reads or
 * persists it. That's what satisfies "don't store secrets in plain
 * SharedPreferences": there simply is no secret material for this app to
 * store in the first place.
 */
class DriveBackupPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("majorgym_drive_backup", Context.MODE_PRIVATE)

    /** Display-only identifier for the connected Google account. Presence of
     *  this (non-null) is also used as "is Drive connected". */
    var connectedAccountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_EMAIL, value).apply()

    var autoBackupEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ENABLED, value).apply()

    /** Minutes since local midnight. Default 23:59 per spec section 6. */
    var backupTimeMinutes: Int
        get() = prefs.getInt(KEY_BACKUP_TIME, DEFAULT_BACKUP_TIME_MINUTES)
        set(value) = prefs.edit().putInt(KEY_BACKUP_TIME, value).apply()

    /** Days to keep backups for, or [RETENTION_FOREVER]. Default 30 (spec 15). */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, 30)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()

    /** Cached Drive folder id for "Major Gym Backups" so it isn't re-resolved
     *  (or accidentally re-created) on every single backup. */
    var driveFolderId: String?
        get() = prefs.getString(KEY_FOLDER_ID, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_ID, value).apply()

    var lastBackupMillis: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_MILLIS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP_MILLIS, value).apply()

    var lastBackupSizeBytes: Long
        get() = prefs.getLong(KEY_LAST_BACKUP_SIZE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP_SIZE, value).apply()

    var lastBackupStatus: DriveBackupStatus
        get() = runCatching {
            DriveBackupStatus.valueOf(prefs.getString(KEY_LAST_STATUS, DriveBackupStatus.NONE.name)!!)
        }.getOrDefault(DriveBackupStatus.NONE)
        set(value) = prefs.edit().putString(KEY_LAST_STATUS, value.name).apply()

    var lastBackupError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    var nextBackupMillis: Long
        get() = prefs.getLong(KEY_NEXT_BACKUP_MILLIS, 0L)
        set(value) = prefs.edit().putLong(KEY_NEXT_BACKUP_MILLIS, value).apply()

    fun history(): List<DriveBackupHistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DriveBackupHistoryEntry(
                timestampMillis = o.optLong("t", 0L),
                sizeBytes = o.optLong("sz", 0L),
                status = runCatching { DriveBackupStatus.valueOf(o.optString("st", "NONE")) }
                    .getOrDefault(DriveBackupStatus.NONE),
                fileName = o.optString("fn", ""),
                driveFileId = o.optString("id", "").takeIf { it.isNotBlank() },
                errorMessage = o.optString("err", "").takeIf { it.isNotBlank() }
            )
        }.sortedByDescending { it.timestampMillis }
    }

    /** Newest first; capped locally at 100 entries regardless of Drive-side
     *  retention (this is just the on-screen log, not the source of truth for
     *  what's actually on Drive — [DriveBackupManager.listRemoteBackups] is). */
    fun addHistoryEntry(entry: DriveBackupHistoryEntry) {
        val trimmed = (listOf(entry) + history()).take(100)
        writeHistory(trimmed)
    }

    fun removeHistoryEntryByFileId(driveFileId: String) {
        writeHistory(history().filterNot { it.driveFileId == driveFileId })
    }

    private fun writeHistory(entries: List<DriveBackupHistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("t", e.timestampMillis)
                put("sz", e.sizeBytes)
                put("st", e.status.name)
                put("fn", e.fileName)
                e.driveFileId?.let { put("id", it) }
                e.errorMessage?.let { put("err", it) }
            })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    /** Called on Disconnect / Change Account: clears everything tied to the
     *  Drive connection. Never touches member data, the existing manual
     *  export/import, or backup history (kept as a record of what happened). */
    fun clearConnection() {
        prefs.edit()
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_FOLDER_ID)
            .putBoolean(KEY_AUTO_ENABLED, false)
            .putLong(KEY_NEXT_BACKUP_MILLIS, 0L)
            .apply()
    }

    companion object {
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_AUTO_ENABLED = "auto_enabled"
        private const val KEY_BACKUP_TIME = "backup_time_minutes"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_FOLDER_ID = "drive_folder_id"
        private const val KEY_LAST_BACKUP_MILLIS = "last_backup_millis"
        private const val KEY_LAST_BACKUP_SIZE = "last_backup_size"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_NEXT_BACKUP_MILLIS = "next_backup_millis"
        private const val KEY_HISTORY = "history"

        const val RETENTION_FOREVER = -1
        const val DEFAULT_BACKUP_TIME_MINUTES = 23 * 60 + 59 // 11:59 PM

        /** Standard retention choices shown in the UI (spec section 15). */
        val RETENTION_OPTIONS: List<Pair<Int, String>> = listOf(
            7 to "7 Days",
            30 to "30 Days",
            90 to "90 Days",
            365 to "1 Year",
            RETENTION_FOREVER to "Forever"
        )
    }
}
