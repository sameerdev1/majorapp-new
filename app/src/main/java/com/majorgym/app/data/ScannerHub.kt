package com.majorgym.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ScannerHub"

/**
 * Owns the ONE persistent native connection to the physical SecuGen scanner
 * for the whole app process, instead of every consumer — the kiosk
 * background loop, the enrollment screen — opening and closing its own
 * [FingerprintScanner] around every single handoff between them.
 *
 * ## Why this exists
 * Every round of fixes before this one patched a genuine *race* somewhere in
 * the repeated close-then-reopen cycle between the kiosk loop and
 * enrollment (fire-and-forget close vs. an immediate reopen; overlapping
 * stop/start hitting the same service instance; a premature bare
 * `stopSelf()`). Each of those was real and each fix was correct — but the
 * scanner kept degrading anyway, faster each time, and eventually stopped
 * responding to check-ins entirely even after physically unplugging and
 * replugging the reader. That last symptom is the tell: once a cheap USB
 * fingerprint reader's own firmware gets wedged from being repeatedly
 * re-initialized in quick succession (Init/OpenDevice/CloseDevice/Close,
 * over and over, once per enrollment and once per screen transition), the
 * cable itself isn't what's holding the bad state — the reader is. No
 * amount of race-freedom in *when* we reopen it fixes a problem caused by
 * *how often* we reopen it.
 *
 * The fix is to stop doing that: open the native device ONCE, the first
 * time anything needs it, and keep that same connection open for as long as
 * the reader is physically attached. [FingerprintKioskService] and the
 * enrollment screen now both borrow capture/match calls from this one
 * already-open connection via [ensureOpen] (idempotent — a no-op if a
 * session is already live, never a fresh Init/Open cycle) and [current].
 * [com.majorgym.app.kiosk.ScannerOwnership] still coordinates *whose turn it
 * is to be actively capturing* between them, exactly as before — it just no
 * longer triggers opening or closing the device itself.
 *
 * The device is only ever actually closed in [handleDetach], fired from a
 * real `ACTION_USB_DEVICE_DETACHED` broadcast — the hardware itself telling
 * us it's gone — never on ordinary navigation between screens.
 */
object ScannerHub {
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    @Volatile private var scanner: FingerprintScanner? = null
    @Volatile private var detachReceiverRegistered = false

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                Log.w(TAG, "SCANNER_HUB_DETACHED — releasing session; next attach starts a clean one")
                // Fire-and-forget onto hubScope is fine here specifically: the
                // physical device is already gone by the time this broadcast
                // fires, so there's no live capture in flight for this release
                // to race against — unlike every other close() in this app's
                // history, which is exactly why those all needed to be awaited.
                hubScope.launch { forceRelease() }
            }
        }
    }

    /**
     * Opens the device if no session is live yet; otherwise returns Success
     * immediately without touching the native SDK at all. This is what both
     * the kiosk loop and enrollment call now instead of their own
     * `FingerprintScanner(...).open()`.
     */
    suspend fun ensureOpen(context: Context): FingerprintScanner.OpenResult {
        ensureDetachReceiverRegistered(context)
        return lifecycleMutex.withLock {
            val existing = scanner
            if (existing != null) {
                return@withLock FingerprintScanner.OpenResult.Success
            }
            Log.d(TAG, "SCANNER_HUB_INIT_START opening the one persistent session")
            val fresh = FingerprintScanner(context.applicationContext)
            val result = fresh.open()
            if (result is FingerprintScanner.OpenResult.Success) {
                scanner = fresh
                Log.d(TAG, "SCANNER_HUB_INIT_SUCCESS")
            } else {
                // Don't leave a half-open instance sitting around — the next
                // ensureOpen() call should start completely clean.
                runCatching { fresh.closeAndAwait() }
            }
            result
        }
    }

    /** The live, already-open scanner, or null if no session is currently open
     *  (never attached yet, or released after a real detach). Callers should
     *  always call [ensureOpen] first and use its result rather than assuming
     *  this is non-null. */
    fun current(): FingerprintScanner? = scanner

    /** True once a session has genuinely been opened at least once — used only
     *  for logging/diagnostics, not for control flow. */
    fun isOpen(): Boolean = scanner != null

    private fun ensureDetachReceiverRegistered(context: Context) {
        if (detachReceiverRegistered) return
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        runCatching {
            val appCtx = context.applicationContext
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(detachReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appCtx.registerReceiver(detachReceiver, filter)
            }
            detachReceiverRegistered = true
        }.onFailure { Log.e(TAG, "SCANNER_HUB_EXCEPTION registering detach receiver: ${it.message}", it) }
    }

    private suspend fun forceRelease() {
        lifecycleMutex.withLock {
            val existing = scanner
            scanner = null
            if (existing != null) {
                runCatching { existing.closeAndAwait() }
                    .onFailure { Log.e(TAG, "SCANNER_HUB_EXCEPTION during detach release: ${it.message}", it) }
            }
        }
    }
}
