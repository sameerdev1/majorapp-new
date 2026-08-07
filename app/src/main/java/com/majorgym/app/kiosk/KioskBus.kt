package com.majorgym.app.kiosk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A resolved fingerprint scan, deliberately kept free of any UI types (no
 * [com.majorgym.app.ui.KioskPhase], no [com.majorgym.app.data.Member]) so the
 * background [FingerprintKioskService] — which has no Activity/Compose context —
 * can produce these on its own. The UI layer looks up the full [com.majorgym.app.data.Member]
 * by [matchedMemberId] and maps this into whatever it needs to render.
 */
data class KioskEvent(
    val matchedMemberId: String?,
    val recognized: Boolean,
    val expired: Boolean
)

/**
 * In-memory hand-off point between [FingerprintKioskService] (always the one
 * doing the actual scanning, whether the app is foreground or background) and
 * whatever is currently rendering the result (MainActivity's existing overlay).
 *
 * [current] is null while idle/listening. The service sets it the moment a scan
 * resolves, holds it for the configured display duration, then clears it back to
 * null itself — the UI layer only ever reads this, it never clears it, so the
 * exact same result is shown correctly whether the app was already in the
 * foreground or got brought forward by the service mid-display.
 */
object KioskBus {
    private val _current = MutableStateFlow<KioskEvent?>(null)
    val current: StateFlow<KioskEvent?> = _current

    /** Only [FingerprintKioskService] should call this. */
    internal fun publish(event: KioskEvent?) {
        _current.value = event
    }
}
