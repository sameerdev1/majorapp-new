package com.majorgym.app.data

import android.content.Context
import android.content.SharedPreferences

/** Records the result of the most recent automatic backup attempt, so the
 *  Backup & Restore screen can show real status ("Backup successful" /
 *  "Backup failed") instead of assuming success just because a job ran. */
class BackupStatusPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("majorgym_backup_status", Context.MODE_PRIVATE)

    var lastAutoBackupMillis: Long
        get() = prefs.getLong(KEY_LAST_MILLIS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_MILLIS, value).apply()

    var lastAutoBackupSuccess: Boolean
        get() = prefs.getBoolean(KEY_LAST_SUCCESS, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_SUCCESS, value).apply()

    var lastAutoBackupError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_ERROR, value).apply()

    fun recordSuccess(atMillis: Long) {
        prefs.edit()
            .putLong(KEY_LAST_MILLIS, atMillis)
            .putBoolean(KEY_LAST_SUCCESS, true)
            .putString(KEY_LAST_ERROR, null)
            .apply()
    }

    fun recordFailure(atMillis: Long, reason: String) {
        prefs.edit()
            .putLong(KEY_LAST_MILLIS, atMillis)
            .putBoolean(KEY_LAST_SUCCESS, false)
            .putString(KEY_LAST_ERROR, reason)
            .apply()
    }

    companion object {
        private const val KEY_LAST_MILLIS = "last_auto_backup_millis"
        private const val KEY_LAST_SUCCESS = "last_auto_backup_success"
        private const val KEY_LAST_ERROR = "last_auto_backup_error"
    }
}
