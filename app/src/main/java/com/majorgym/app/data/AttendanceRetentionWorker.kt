package com.majorgym.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Change 1: runs once a day (scheduled via [schedule] from MainActivity,
 * alongside - not instead of - [MembershipCleanupWorker.schedule]) and
 * deletes attendance records older than [ATTENDANCE_RETENTION_MONTHS].
 *
 * Deliberately separate from [MembershipCleanupWorker]: that worker's job is
 * member deletion with its own two-step confirmation safeguard, and its
 * timing/conditions must not change (Change 5). This worker does one small,
 * unrelated thing - a plain indexed range delete on attendance_records - and
 * nothing else. It never touches members, attendance recording, QR/
 * fingerprint check-in logic, or Morning/Evening grouping.
 *
 * Safe to run repeatedly / after a missed run / after a reboot: every run
 * just deletes whatever is currently older than the rolling cutoff, so it's
 * naturally idempotent and can never delete something still inside the
 * retention window.
 */
class AttendanceRetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            Repository(applicationContext).cleanupOldAttendance()
            Result.success()
        }.getOrElse {
            // Any failure (DB hiccup, etc.) just retries on WorkManager's own
            // schedule - never treated as a reason to delete more or less.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "attendance_retention_cleanup"

        /** Call once (e.g. from MainActivity.onCreate - safe/cheap to call on
         *  every launch, WorkManager de-dupes via KEEP). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AttendanceRetentionWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
