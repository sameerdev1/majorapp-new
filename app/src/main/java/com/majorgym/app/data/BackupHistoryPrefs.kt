package com.majorgym.app.data

import android.content.Context
import org.json.JSONArray

/**
 * BACKUP HISTORY - a lightweight log of *when* a backup was taken (date/time
 * only). This is deliberately NOT a second copy of the backup itself: no
 * member data, photos, membership plans, attendance, ID proofs, or
 * fingerprint data is ever written here, only a timestamp per entry. The
 * real backup file (with all of that content) is still produced exactly as
 * before by [BackupService.createZipBackup] / [BackupManager.exportJson] -
 * this class only notes that it happened and when.
 *
 * Stored in its own SharedPreferences file (same pattern as [SyncPrefs]) so
 * no change to the Room database/schema was needed for this feature.
 */
class BackupHistoryPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("majorgym_backup_history", Context.MODE_PRIVATE)

    /** Call right after a real backup file has actually been written to disk.
     *  Appends "now", then immediately prunes anything past the retention
     *  window so the stored list never grows unbounded. */
    fun recordBackupTaken() {
        val now = System.currentTimeMillis()
        val updated = (rawEntries() + now)
        save(prune(updated))
    }

    /** Backup timestamps within the retention window, newest first. */
    fun entries(): List<Long> = prune(rawEntries()).sortedDescending()

    private fun prune(list: List<Long>): List<Long> {
        val cutoff = addMonthsMillis(System.currentTimeMillis(), -RETENTION_MONTHS)
        return list.filter { it >= cutoff }
    }

    private fun rawEntries(): List<Long> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getLong(it) }
    }

    private fun save(list: List<Long>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_ENTRIES = "entries"

        /** Backup History is kept for the latest 3 months only. */
        private const val RETENTION_MONTHS = 3L
    }
}
