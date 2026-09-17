package com.majorgym.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** How long after expiry, with no renewal, a member is moved to Hold
 *  (see [MembershipState]). "More than 2 months" per spec - a member expired
 *  for exactly 2 months must NOT be moved yet, only once they cross that
 *  threshold. */
private const val HOLD_ELIGIBILITY_MONTHS = 2L

/**
 * Runs once a day (scheduled via [schedule] from MainActivity) and implements
 * the Hold Members lifecycle (fixes #4/#5/#9):
 *
 *  - A member expired more than [HOLD_ELIGIBILITY_MONTHS] months, with no
 *    renewal since, is moved to [MembershipState.HOLD]. Nothing is ever
 *    deleted - the member row, photos, ID proof, fingerprint template, and
 *    full history all stay exactly as they were. A Hold member simply stops
 *    appearing in the normal Members list/counts (see MainActivity) and stops
 *    being included in the fingerprint attendance search (see
 *    FingerprintKioskService) - scanning their fingerprint falls through to
 *    the existing "Member Not Found" behavior automatically.
 *  - Self-healing safety net: if a member already on Hold is no longer
 *    eligible (their expiry moved into the eligible window again - most
 *    commonly because RenewScreen already renewed and un-Held them
 *    immediately, but also covers any other path that changes
 *    [Member.expiryMillis], e.g. a synced correction from another device),
 *    this clears [MembershipState.HOLD] back to [MembershipState.ACTIVE] on
 *    the very next run. This never fires for a member who was never Hold in
 *    the first place.
 *
 * This worker used to also permanently delete very-long-expired members
 * (Feature 4 of an earlier pass) - that automatic deletion has been removed
 * entirely per the current spec: a member must never be permanently deleted
 * just because their membership expired. Manual deletion (Profile -> Delete)
 * is completely untouched and still works exactly as before.
 *
 * Safe to run repeatedly / after a missed run / after a reboot - every run
 * recomputes eligibility fresh from current data rather than trusting any
 * external counter, so it's naturally idempotent.
 */
class MembershipHoldWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val repo = Repository(applicationContext)
            val now = System.currentTimeMillis()
            val all = repo.allOnce()

            for (member in all) {
                val eligibleForHold = now >= addMonthsMillis(member.expiryMillis, HOLD_ELIGIBILITY_MONTHS)

                when {
                    // Expired more than 2 months, never renewed since, and not
                    // already on Hold - move them, preserving every field.
                    eligibleForHold && member.membershipState != MembershipState.HOLD -> {
                        repo.save(member.copy(membershipState = MembershipState.HOLD, updatedAtMillis = now))
                    }

                    // Already on Hold but no longer eligible (renewed, or their
                    // expiry otherwise moved forward) - self-heal back to ACTIVE.
                    !eligibleForHold && member.membershipState == MembershipState.HOLD -> {
                        repo.save(member.copy(membershipState = MembershipState.ACTIVE, updatedAtMillis = now))
                    }

                    else -> Unit
                }
            }
            Result.success()
        }.getOrElse {
            // Any failure (DB hiccup, etc.) just retries on WorkManager's own
            // schedule - never treated as a reason to touch member data.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "membership_hold_lifecycle"

        /** Call once (e.g. from MainActivity.onCreate — safe/cheap to call on
         *  every launch, WorkManager de-dupes via KEEP). Also cancels the
         *  old "membership_cleanup" auto-delete job by name, if a previous
         *  install ever scheduled it - that worker class no longer exists,
         *  so leaving its stale schedule around would just fail silently on
         *  WorkManager's own retry cadence forever for no benefit. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork("membership_cleanup")
            val request = PeriodicWorkRequestBuilder<MembershipHoldWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
