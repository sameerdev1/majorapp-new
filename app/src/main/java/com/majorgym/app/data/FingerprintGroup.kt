package com.majorgym.app.data

import java.time.LocalTime

/**
 * Splits enrolled members into two buckets purely to shrink the 1:N fingerprint
 * search space (see [FingerprintKioskService] in the kiosk package): at any
 * given moment only the current time-of-day's group is searched first, with
 * the other group used only as a fallback. This has no effect on 1:1 matching
 * during enrollment ([FingerprintScanner.match]) — grouping only changes which
 * *candidate list* the kiosk loop iterates over.
 *
 * [UNASSIGNED] exists solely for members enrolled before this feature shipped
 * (see the 7->8 migration in [AppDatabase]): they carry no group opinion, so
 * the kiosk service includes them in *both* the morning and evening buckets
 * rather than guessing — nobody who could match before this feature should
 * ever stop matching because of it.
 */
enum class FingerprintGroup {
    MORNING, EVENING, UNASSIGNED;

    /** Value persisted on [Member.fingerprintGroup]. Kept as a plain lowercase
     *  string (matching the rest of this table's string columns, e.g. `plan`)
     *  rather than a Room-mapped enum, so the migration is a single cheap
     *  ADD COLUMN with a TEXT default. */
    fun toStorageValue(): String = when (this) {
        MORNING -> "morning"
        EVENING -> "evening"
        UNASSIGNED -> ""
    }

    companion object {
        fun fromStorageValue(value: String): FingerprintGroup = when (value) {
            "morning" -> MORNING
            "evening" -> EVENING
            else -> UNASSIGNED
        }
    }
}

/**
 * Single source of truth for the Morning/Evening time boundary, so it's never
 * duplicated across the enrollment screen and the kiosk matching loop (spec:
 * "one centralized configuration/constants section"). Change the two hours
 * here to retune the split; nothing else needs to change.
 */
object FingerprintGroupConfig {
    /** Morning runs [MORNING_START_HOUR, EVENING_START_HOUR). */
    const val MORNING_START_HOUR = 6   // 06:00 AM
    /** Evening runs [EVENING_START_HOUR, 24) and [0, MORNING_START_HOUR). */
    const val EVENING_START_HOUR = 15  // 03:00 PM

    /** The group whose templates should be searched *first* right now, based
     *  on the device's local clock. Never returns [FingerprintGroup.UNASSIGNED]. */
    fun currentGroup(now: LocalTime = LocalTime.now()): FingerprintGroup {
        val hour = now.hour
        return if (hour in MORNING_START_HOUR until EVENING_START_HOUR) {
            FingerprintGroup.MORNING
        } else {
            FingerprintGroup.EVENING
        }
    }

    /** The other of the two real groups — used to pick the fallback bucket. */
    fun other(group: FingerprintGroup): FingerprintGroup = when (group) {
        FingerprintGroup.MORNING -> FingerprintGroup.EVENING
        FingerprintGroup.EVENING -> FingerprintGroup.MORNING
        FingerprintGroup.UNASSIGNED -> FingerprintGroup.UNASSIGNED
    }
}
