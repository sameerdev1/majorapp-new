package com.majorgym.app.kiosk

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The SecuGen Hamster Pro 20 is a single physical USB device — only one
 * consumer, [FingerprintKioskService]'s background loop or the enrollment
 * screen, should be actively capturing on it at a time.
 *
 * The connection itself is no longer opened/closed on this handoff at all —
 * see [com.majorgym.app.data.ScannerHub], which owns one persistent native
 * connection for the whole app process. This object now only arbitrates
 * *turn-taking* for capture calls on that shared connection: whichever owner
 * holds it is the one whose capture loop should be running right now. That's
 * a narrower job than it used to have (it used to gate real open/close
 * timing, back when every handoff meant a fresh native Init/Open/Close
 * cycle), but callers use it exactly the same way as before — acquire before
 * capturing, release when done, awaitReleased before taking over.
 */
object ScannerOwnership {
    private const val TAG = "ScannerOwnership"

    enum class Owner { NONE, KIOSK, ENROLLMENT }

    private val _owner = MutableStateFlow(Owner.NONE)
    val owner: StateFlow<Owner> = _owner

    /** Marks [who] as currently holding the physical device open. */
    fun acquire(who: Owner) {
        Log.d(TAG, "SCANNER_OWNER_ACQUIRE owner=$who")
        _owner.value = who
    }

    /** Marks the device as released, but only if [who] was actually the current
     *  owner — prevents a stale release from clobbering a different owner that
     *  has since acquired it (e.g. a delayed cleanup call). */
    fun release(who: Owner) {
        if (_owner.value == who) {
            Log.d(TAG, "SCANNER_OWNER_RELEASE owner=$who")
            _owner.value = Owner.NONE
        }
    }

    /**
     * Suspends until nobody owns the device (or [timeoutMs] elapses). Used by
     * the enrollment screen before it opens its own scanner, so it never opens
     * concurrently with the background kiosk loop. Returns true if the device
     * was confirmed free, false on timeout (caller decides how to proceed).
     */
    suspend fun awaitReleased(timeoutMs: Long): Boolean {
        if (_owner.value == Owner.NONE) return true
        val result = withTimeoutOrNull(timeoutMs) {
            owner.first { it == Owner.NONE }
        }
        return result != null
    }
}
