package com.majorgym.app.kiosk

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.majorgym.app.R
import com.majorgym.app.data.daysBetweenNow

/**
 * Finer-grained membership status used only for picking which check-in audio
 * clip to play. Deliberately separate from [com.majorgym.app.data.MemberStatus]
 * (ACTIVE/EXPIRING/EXPIRED, used everywhere else for badges/UI) rather than
 * changing that enum's meaning — this one distinguishes "expires today" and
 * "expires tomorrow" as their own states, which the existing 7-day EXPIRING
 * bucket doesn't. Both are computed from the same [daysBetweenNow] source of
 * truth, so they never disagree about *whether* a membership has expired.
 */
enum class MembershipAudioStatus { EXPIRED, EXPIRING_TODAY, EXPIRING_IN_1_DAY, ACTIVE }

/** Priority order per spec: expired beats expiring-today beats expiring-tomorrow
 *  beats active. Uses the device's local calendar date, same as every other
 *  expiry calculation in this app (see [daysBetweenNow]). */
fun membershipAudioStatusOf(expiryMillis: Long): MembershipAudioStatus {
    val days = daysBetweenNow(expiryMillis)
    return when {
        days < 0L -> MembershipAudioStatus.EXPIRED
        days == 0L -> MembershipAudioStatus.EXPIRING_TODAY
        days == 1L -> MembershipAudioStatus.EXPIRING_IN_1_DAY
        else -> MembershipAudioStatus.ACTIVE
    }
}

/**
 * Plays the bundled ACTIVE / EXPIRED / EXPIRING_TODAY / EXPIRING_IN_1_DAY MP3s
 * (res/raw) after a successful fingerprint match. Intentionally its own tiny
 * object, separate from [KioskSound]'s ToneGenerator beeps — those stay exactly
 * as they were; this only adds the new membership-status clip alongside them.
 *
 * Safety, per spec section 15:
 *  - only one clip plays at a time: starting a new one always releases any
 *    still-playing prior instance first (never overlaps).
 *  - the player releases itself automatically on completion or error, so
 *    nothing can be left dangling to double-release later.
 *  - every call is wrapped in runCatching — a playback failure here must never
 *    take down the scan loop or block the next fingerprint capture.
 */
internal object MembershipAudioPlayer {
    private const val TAG = "MembershipAudio"

    @Volatile private var current: MediaPlayer? = null

    fun play(context: Context, status: MembershipAudioStatus) {
        runCatching {
            // Stop/release whatever was already playing so clips never overlap.
            stop()

            val rawRes = when (status) {
                MembershipAudioStatus.ACTIVE -> R.raw.active
                MembershipAudioStatus.EXPIRED -> R.raw.expired
                MembershipAudioStatus.EXPIRING_TODAY -> R.raw.expiring_today
                MembershipAudioStatus.EXPIRING_IN_1_DAY -> R.raw.expiring_in_1_day
            }

            val player = MediaPlayer.create(context.applicationContext, rawRes) ?: run {
                Log.w(TAG, "MediaPlayer.create returned null for $status")
                return
            }
            current = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setOnCompletionListener { mp ->
                runCatching { mp.release() }
                if (current === mp) current = null
            }
            player.setOnErrorListener { mp, _, _ ->
                runCatching { mp.release() }
                if (current === mp) current = null
                true
            }
            player.start()
        }.onFailure { e -> Log.w(TAG, "Playback failed for $status", e) }
    }

    /** Safe to call any time, including when nothing is playing. */
    fun stop() {
        val player = current
        current = null
        if (player != null) {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
    }
}
