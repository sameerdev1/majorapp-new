package com.majorgym.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Filename timestamp format - safe on Android/every filesystem (no ':', no '/'). */
private val BACKUP_FILENAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

/** The target daily automatic-backup time. Local device time, per spec section 3. */
private val TARGET_TIME: LocalTime = LocalTime.of(23, 59)

/** Default number of automatic backups to retain (spec section 5). */
private const val AUTO_BACKUP_RETENTION = 30

/**
 * Runs once a day and produces a verified ZIP automatic backup, using the
 * exact same generate -> compress -> verify pipeline as Backup Now and Export
 * Backup (see [BackupService]) - no duplicated backup logic.
 *
 * Scheduling caveat (spec section 3): Android's WorkManager, without the
 * user separately granting the "Alarms & reminders" / exact-alarm permission,
 * cannot guarantee execution at the exact wall-clock minute - Doze and battery
 * optimization can shift a run by anywhere from minutes to a couple of hours,
 * and periodic work intervals are measured from the previous run rather than
 * re-anchored to the wall clock, so timing can drift over many days. This is
 * the safest approach available without asking the owner for that extra
 * permission. [schedule] below is the one and only place that decides *how*
 * the job is scheduled, so swapping in an exact-alarm approach later (should
 * that permission ever be added) only means changing this one function.
 *
 * Never reports success unless a real, reopened, re-parsed ZIP exists on disk
 * - see [BackupService.createZipBackup].
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        val statusPrefs = BackupStatusPrefs(applicationContext)
        val now = System.currentTimeMillis()
        val label = LocalDateTime.now().format(BACKUP_FILENAME_FORMAT)
        val destFile = repo.newAutoBackupFile(label)

        return try {
            BackupService.createZipBackup(applicationContext, repo, destFile)
            // Only prune older automatic backups after this one is confirmed
            // saved and verified - a failed attempt above never reaches here,
            // so a bad new backup can never cost a prior good one.
            repo.pruneAutoBackups(AUTO_BACKUP_RETENTION)
            statusPrefs.recordSuccess(now)
            Result.success()
        } catch (e: Exception) {
            statusPrefs.recordFailure(now, e.message ?: "Automatic backup failed.")
            // Periodic WorkManager requests ignore Result.retry() and simply
            // wait for their next scheduled run regardless, so this just
            // reports the failure for logging/telemetry purposes.
            Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "automatic_zip_backup"

        /** Call once (e.g. from MainActivity.onCreate - safe/cheap on every
         *  launch, WorkManager's KEEP policy no-ops if already scheduled and
         *  never resets the run cadence or loses the retention history). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(millisUntilNextTargetTime(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Milliseconds from now until the next occurrence of [TARGET_TIME] in
         *  the device's local timezone - today's if it hasn't happened yet,
         *  otherwise tomorrow's. Isolated here so the "what time do we target"
         *  decision lives in exactly one place. */
        private fun millisUntilNextTargetTime(): Long {
            val zone = ZoneId.systemDefault()
            val now = LocalDateTime.now(zone)
            var target = now.toLocalDate().atTime(TARGET_TIME)
            if (!target.isAfter(now)) target = target.plusDays(1)
            return java.time.Duration.between(now, target).toMillis()
        }
    }
}
