package com.majorgym.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** How many days after expiry, with no renewal, a member becomes eligible
 *  for the 30-Day Expired Member Archive (see [Repository.archiveMember]).
 *  A renewal moves [Member.expiryMillis] into the future, which immediately
 *  makes the member ineligible again - the same way it always has. */
private const val ARCHIVE_THRESHOLD_DAYS = 30L

/**
 * Runs once a day (scheduled via [schedule] from MainActivity) and archives
 * memberships that have been expired 30+ days and were never renewed.
 * Replaces the previous behavior (an automatic, unrecoverable delete after
 * 4 months) with the 30-Day Expired Member Archive: a lightweight
 * [ArchivedMember] record is created first, the member's attendance history
 * is permanently deleted, and only then is the operational [Member] row
 * removed - via the same [Repository.deleteWithFiles] the manual "Delete
 * Member" action already uses, so nothing about that path changes.
 *
 * Deliberately does NOT treat a check-in alone as reason to keep an
 * account - only a renewal (which moves [Member.expiryMillis] forward)
 * rescues a member from archival, same as before.
 *
 * Safe to run repeatedly / after a missed run / after a reboot: every run
 * recomputes eligibility fresh from current data, and [Repository.archiveMember]
 * is itself idempotent (a member that's already archived, or whose archive
 * row already exists, is never processed twice - see [ArchivedMemberDao]).
 */
class MembershipCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val repo = Repository(applicationContext)
            val all = repo.allOnce()

            for (member in all) {
                val daysSinceExpiry = -daysBetweenNow(member.expiryMillis)
                if (daysSinceExpiry >= ARCHIVE_THRESHOLD_DAYS) {
                    repo.archiveMember(member)
                }
            }
            Result.success()
        }.getOrElse {
            // Any failure (DB hiccup, etc.) just retries on WorkManager's own
            // schedule - never treated as "so archive/delete everything anyway".
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "membership_cleanup"

        /** Call once (e.g. from MainActivity.onCreate — safe/cheap to call on
         *  every launch, WorkManager de-dupes via KEEP). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MembershipCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
