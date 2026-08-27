package com.majorgym.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Runs the automatic Drive backup, then reschedules itself for the next
 * occurrence of the configured backup time (WorkManager has no built-in
 * "run daily at this clock time" request type, so this chains one-shot
 * work — the same technique the platform docs recommend for this case).
 *
 * The work request itself carries a NetworkType.CONNECTED constraint, so if
 * the backup time arrives with no internet, this simply doesn't run yet
 * (WorkManager waits for connectivity) rather than firing and failing —
 * that, plus [DriveBackupManager.performBackup] setting an explicit
 * OFFLINE_PENDING status up front, is what satisfies spec section 7 ("never
 * report success unless the upload actually completed, retry automatically
 * when appropriate") without a hand-rolled polling loop.
 *
 * Per spec section 6: this can only ever be "approximately around" the
 * selected time — Android background execution (Doze, battery
 * optimization, OEM restrictions) can delay any job, WorkManager included.
 */
class BackupScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = DriveBackupPrefs(applicationContext)

        // Settings may have changed (disabled / disconnected) between when this
        // was scheduled and when it actually runs — re-check before doing anything.
        if (!prefs.autoBackupEnabled || prefs.connectedAccountEmail == null) {
            return Result.success()
        }

        val repository = Repository(applicationContext)
        val drive = DriveBackupManager(applicationContext)

        if (!drive.isOnline()) {
            // Let WorkManager's own network constraint + backoff retry this —
            // status is already set to OFFLINE_PENDING by performBackup's own
            // check the next time it actually runs; set it here too so the UI
            // reflects reality immediately rather than waiting for that retry.
            prefs.lastBackupStatus = DriveBackupStatus.OFFLINE_PENDING
            prefs.lastBackupError = "Internet connection unavailable"
            return Result.retry()
        }

        drive.performBackup(repository, prefs)
        // Whether this attempt succeeded or hit a real (non-connectivity)
        // failure, move on to tomorrow's slot rather than retrying forever —
        // a genuine failure (e.g. auth expired) won't fix itself by retrying
        // moments later, and the owner can always use "Backup Now" or
        // "Reconnect Google Drive" in the meantime.
        DriveBackupScheduler.reschedule(applicationContext)
        return Result.success()
    }

    companion object {
        internal const val UNIQUE_WORK_NAME = "drive_auto_backup"
    }
}

/** Schedules/cancels [BackupScheduleWorker] based on current settings. */
object DriveBackupScheduler {

    /** Re-reads settings and (re)enqueues the next run. Safe/idempotent to
     *  call any time settings change (enable/disable, time change, connect/
     *  disconnect) — always replaces whatever was previously queued. */
    fun reschedule(context: Context) {
        val prefs = DriveBackupPrefs(context)
        val wm = WorkManager.getInstance(context)

        if (!prefs.autoBackupEnabled || prefs.connectedAccountEmail == null) {
            wm.cancelUniqueWork(BackupScheduleWorker.UNIQUE_WORK_NAME)
            prefs.nextBackupMillis = 0L
            return
        }

        val delayMillis = millisUntilNext(prefs.backupTimeMinutes)
        prefs.nextBackupMillis = System.currentTimeMillis() + delayMillis

        val request = OneTimeWorkRequestBuilder<BackupScheduleWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        wm.enqueueUniqueWork(BackupScheduleWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Re-arms the schedule only if it looks stale/missing (e.g. first launch
     *  after enabling, or after a very long time away) — never resets a
     *  still-valid future schedule just because the app was reopened. */
    fun ensureScheduled(context: Context) {
        val prefs = DriveBackupPrefs(context)
        if (prefs.autoBackupEnabled && prefs.connectedAccountEmail != null &&
            prefs.nextBackupMillis <= System.currentTimeMillis()
        ) {
            reschedule(context)
        }
    }

    private fun millisUntilNext(minutesOfDay: Int): Long {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(minutesOfDay / 60, minutesOfDay % 60)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).toMillis().coerceAtLeast(0L)
    }
}
