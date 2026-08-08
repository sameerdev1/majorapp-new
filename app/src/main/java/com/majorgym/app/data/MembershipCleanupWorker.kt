package com.majorgym.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** How long after expiry, with no renewal, an account becomes eligible for deletion. */
private const val DELETION_ELIGIBILITY_MONTHS = 4L

/** How long a member must stay eligible — confirmed on a second run — before
 *  they're actually deleted. This, together with [Member.pendingDeletionMillis],
 *  is the safeguard against a one-off clock glitch or sync hiccup causing an
 *  instant, irreversible deletion (per the agreed spec). */
private const val CONFIRMATION_WINDOW_MILLIS = 20 * 60 * 60 * 1000L // ~20h: safely under the ~24h run cadence with margin either side.

/**
 * Runs once a day (scheduled via [schedule] from MainActivity) and removes
 * memberships that expired 4+ months ago and were never renewed. Deliberately
 * does NOT treat a check-in alone as reason to keep an account — only a
 * renewal (which moves [Member.expiryMillis] forward) rescues a member from
 * this, per the agreed spec.
 *
 * Two-step safeguard, not immediate deletion:
 *   1. First run a member is found eligible → [Member.pendingDeletionMillis]
 *      is set, nothing is deleted yet.
 *   2. Only once still eligible on a LATER run, at least [CONFIRMATION_WINDOW_MILLIS]
 *      after that first flag, is the member actually deleted — via
 *      [Repository.deleteWithFiles], which also removes their photo, ID proof
 *      photo, and fingerprint template, not just the database row.
 *   3. If a member renews at any point in between, [Member.expiryMillis] moves
 *      into the future, they stop being eligible, and [Member.pendingDeletionMillis]
 *      is cleared automatically on the very next run.
 *
 * Safe to run repeatedly / after a missed run / after a reboot — every run
 * recomputes eligibility fresh from current data rather than trusting any
 * external counter, so it's naturally idempotent.
 */
class MembershipCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val repo = Repository(applicationContext)
            val now = System.currentTimeMillis()
            val all = repo.allOnce()

            for (member in all) {
                val eligible = now >= addMonthsMillis(member.expiryMillis, DELETION_ELIGIBILITY_MONTHS)

                when {
                    // No longer eligible (most likely: they renewed) — clear any
                    // stale pending-deletion flag so they're never touched again
                    // until/unless they become eligible again in the future.
                    !eligible && member.pendingDeletionMillis != null -> {
                        repo.save(member.copy(pendingDeletionMillis = null))
                    }

                    // Eligible for the first time — flag it, don't delete yet.
                    eligible && member.pendingDeletionMillis == null -> {
                        repo.save(member.copy(pendingDeletionMillis = now))
                    }

                    // Eligible AND already flagged long enough ago — safe to delete for real.
                    eligible && member.pendingDeletionMillis != null &&
                        now - member.pendingDeletionMillis >= CONFIRMATION_WINDOW_MILLIS -> {
                        repo.deleteWithFiles(member)
                    }

                    // Eligible, flagged, but not long enough ago yet — wait for a later run.
                    else -> Unit
                }
            }
            Result.success()
        }.getOrElse {
            // Any failure (DB hiccup, etc.) just retries on WorkManager's own
            // schedule — never treated as "so delete everything anyway".
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
