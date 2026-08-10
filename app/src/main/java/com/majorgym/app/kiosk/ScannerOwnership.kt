package com.majorgym.app.kiosk

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The SecuGen Hamster Pro 20 is a single physical USB device — only one
 * [com.majorgym.app.data.FingerprintScanner] instance may have it open at a
 * time, whether that's [FingerprintKioskService]'s background identification
 * loop or the enrollment screen's own scanner.
 *
 * Previously the handoff between those two was "fire and forget": the
 * enrollment screen asked the service to stop and then opened its own
 * scanner immediately, with no guarantee the service had actually released
 * the device yet. That race is the root cause of the "scanner error, then
 * back to Home when a finger is placed" bug — two owners ended up touching
 * the same native device concurrently. This object gives callers a real
 * acknowledgment to wait on instead.
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
