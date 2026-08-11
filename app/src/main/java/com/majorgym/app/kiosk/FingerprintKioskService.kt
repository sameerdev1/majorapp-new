package com.majorgym.app.kiosk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.majorgym.app.MainActivity
import com.majorgym.app.data.AppDatabase
import com.majorgym.app.data.FingerprintScanner
import com.majorgym.app.data.Member
import com.majorgym.app.data.MemberStatus
import com.majorgym.app.data.ScannerHub
import com.majorgym.app.data.statusOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "FingerprintKioskSvc"
private const val MATCHED_DISPLAY_MS = 3000L
private const val NOT_RECOGNIZED_DISPLAY_MS = 1500L
private const val LISTEN_SLICE_MS = 4000
/** An isolated capture error is tolerated (see the Error branch in runLoop);
 *  this many IN A ROW is treated as a genuine disconnect rather than a
 *  transient SDK/USB hiccup, and actually stops the loop. */
private const val MAX_CONSECUTIVE_CAPTURE_ERRORS = 5
private const val CAPTURE_ERROR_RETRY_DELAY_MS = 500L
private const val ONGOING_CHANNEL_ID = "fingerprint_kiosk_status"
private const val ALERT_CHANNEL_ID = "fingerprint_kiosk_alert"
private const val ONGOING_NOTIF_ID = 501
private const val ALERT_NOTIF_ID = 502

/**
 * Owns the fingerprint scanner and the continuous scan/match loop for kiosk
 * mode. Runs as a foreground service specifically so listening survives the
 * app being backgrounded (Home button) — a plain Activity-owned coroutine gets
 * suspended by Android's background execution limits within seconds/minutes.
 *
 * Lifecycle, per the agreed spec:
 *  - Only ever shows its (mandatory, Android-required) notification and starts
 *    listening once a scanner is actually found — see [onStartCommand]/[runLoop].
 *    If no device is found, it stops itself immediately and silently; the
 *    caller (MainActivity) is responsible for retrying periodically.
 *  - Pressing Home does NOT stop it — that's the whole point of this service.
 *  - Swiping the app away from Recent Apps DOES stop it — see [onTaskRemoved].
 *  - A hard USB/device error also stops it; MainActivity's retry loop will
 *    bring it back once the device is available again.
 *
 * Every scan result is published to [KioskBus], which the existing overlay UI
 * (unchanged) reads to display exactly as before. Matching + sound + display
 * timing live here now (not in the Activity) so the same behavior holds
 * whether the app is foreground or background.
 */
class FingerprintKioskService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    private var scanner: FingerprintScanner? = null

    /**
     * Serializes every full "open device / run loop / close device" cycle so a
     * new one can never start until the previous one has ACTUALLY finished
     * closing its handle. Without this, a stop request followed quickly by a
     * start request (which happens routinely — every enrollment, and every
     * 3-second poll in KioskOverlay's coordinator) can hit this same Service
     * instance while the old loop's cleanup is still mid-flight on another
     * coroutine: onStartCommand sees [loopJob] already null (stopListeningAndSelf
     * clears it synchronously, before the async cleanup finishes) and launches a
     * second loop that opens a brand-new native handle while the first is still
     * releasing the old one — two coroutines touching the same physical USB
     * device at once. A new runLoop's open() now simply waits its turn here
     * instead of racing the previous one's close().
     */
    private val scannerLifecycleMutex = Mutex()

    /**
     * Enrolled members (with a fingerprint template), refreshed reactively from
     * Room via [runLoop]'s cache collector instead of being re-queried from the
     * database on every single scan. Kept pre-sorted by most-recently-seen
     * first, so a genuine member usually matches within the first few
     * comparisons instead of the app working through the whole roster —
     * Feature 1 of the performance pass. @Volatile because it's written from
     * the Flow-collector coroutine and read from the scan loop, which may run
     * on different threads within the same IO dispatcher pool.
     */
    @Volatile private var enrolledCache: List<Member> = emptyList()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListeningAndSelf(startId)
            return START_NOT_STICKY
        }
        // Already trying/listening — nothing new to do for a repeat start request.
        if (loopJob?.isActive == true) return START_NOT_STICKY

        loopJob = serviceScope.launch { runLoop(startId) }
        return START_NOT_STICKY
    }

    private suspend fun CoroutineScope.runLoop(startId: Int) {
        // Android requires startForeground() to be called within a few seconds of
        // startForegroundService() or the OS kills the process — so this call goes
        // first, before we even know if a device is present. In practice this is a
        // non-issue: MainActivity only ever calls requestStart() after its own
        // lightweight USB device-list check already found a matching SecuGen
        // device (see isScannerConnected), so the immediate failure path below is
        // a rare defensive fallback, not the normal case.
        startForeground(ONGOING_NOTIF_ID, buildOngoingNotification())

        // Everything that touches the physical device — for this entire loop
        // iteration, start to finish — happens under this lock, so an
        // overlapping stop/start against this same Service instance can never
        // launch two loop bodies concurrently. The device itself is no longer
        // opened or closed here at all, though — see ScannerHub for why: it
        // owns the one persistent native connection for the whole app, and
        // this loop just borrows capture calls from it.
        scannerLifecycleMutex.withLock {
        Log.d(TAG, "SCANNER_ENSURE_OPEN background loop")
        val openResult = ScannerHub.ensureOpen(applicationContext)
        val fp = ScannerHub.current()
        if (openResult !is FingerprintScanner.OpenResult.Success || fp == null) {
            Log.w(TAG, "SCANNER_OPEN_FAILED background loop result=$openResult")
            stopForegroundCompat()
            // stopSelf(startId), not bare stopSelf(): if a newer start request
            // has already been delivered to this service by the time we get
            // here, this is a no-op and the service stays alive for that newer
            // request instead of being torn down out from under it — see
            // stopListeningAndSelf's doc for the full story.
            stopSelf(startId)
            return
        }
        scanner = fp
        Log.d(TAG, "SCANNER_BACKGROUND_SCAN_START")
        // Claim ownership only once we're actually about to start capturing,
        // and release it in the finally block below — no matter whether the
        // loop exits normally, hits an SDK error, or is cancelled (e.g.
        // enrollment asked us to stop). Enrollment waits on this via
        // ScannerOwnership before it starts capturing on the same shared
        // connection, so the two are never actively capturing concurrently.
        ScannerOwnership.acquire(ScannerOwnership.Owner.KIOSK)

        var cacheJob: Job? = null
        try {
            // Feature 1: keep the enrolled-members list in memory, updated reactively
            // whenever it changes in the database, instead of querying Room fresh on
            // every single scan attempt.
            cacheJob = launch {
                runCatching {
                    AppDatabase.get(applicationContext).memberDao().getAll().collect { list ->
                        enrolledCache = list.filter { it.fingerprintTemplate != null }
                            .sortedByDescending { it.lastAttendanceMillis ?: 0L }
                    }
                }
            }

            var consecutiveErrors = 0
            while (isActive) {
                when (val capture = fp.captureTemplate(timeoutMs = LISTEN_SLICE_MS)) {
                    is FingerprintScanner.CaptureResult.Success -> {
                        consecutiveErrors = 0
                        var matched: Member? = null
                        for (m in enrolledCache) {
                            if (fp.match(m.fingerprintTemplate!!, capture.template)) {
                                matched = m
                                break
                            }
                        }

                        if (matched != null) {
                            val audioStatus = membershipAudioStatusOf(matched.expiryMillis)
                            val expired = statusOf(matched.expiryMillis) == MemberStatus.EXPIRED
                            KioskSound.playSuccess()
                            KioskBus.publish(KioskEvent(matched.id, recognized = true, expired = expired))
                            // Membership-status audio: only ever reached on a genuine
                            // successful match, using the exact same matched Member used
                            // for the overlay display above — never a separate lookup.
                            MembershipAudioPlayer.play(applicationContext, audioStatus)
                            notifyIfBackgrounded()
                            delay(MATCHED_DISPLAY_MS)
                        } else {
                            KioskSound.playError()
                            KioskBus.publish(KioskEvent(null, recognized = false, expired = false))
                            notifyIfBackgrounded()
                            delay(NOT_RECOGNIZED_DISPLAY_MS)
                        }
                        KioskBus.publish(null)
                    }
                    FingerprintScanner.CaptureResult.Timeout -> {
                        // Nobody scanned during this slice — keep listening silently.
                        consecutiveErrors = 0
                    }
                    is FingerprintScanner.CaptureResult.Error -> {
                        // A single Error here does NOT necessarily mean the device is
                        // gone — these budget USB readers routinely throw an isolated
                        // SDK-level error mid-session (a marginal read, a brief USB
                        // hiccup) that has nothing to do with the device being
                        // unplugged. Treating every one of those as fatal used to tear
                        // the whole service down and force a full re-Init()/OpenDevice()
                        // cycle every single time — and repeatedly re-initializing this
                        // SDK is itself what was wearing the scanner into "unavailable,
                        // please reconnect" after a handful of check-ins, not the errors
                        // themselves. So: tolerate isolated errors like a Timeout, and
                        // only actually give up after several land in a row, which is a
                        // real signal something (e.g. an actual unplug) is wrong.
                        consecutiveErrors++
                        Log.w(TAG, "SCANNER_CAPTURE_FAILED background loop (consecutive=$consecutiveErrors)")
                        if (consecutiveErrors >= MAX_CONSECUTIVE_CAPTURE_ERRORS) {
                            Log.w(TAG, "SCANNER_CAPTURE_FAILED giving up after $consecutiveErrors consecutive errors, stopping")
                            break
                        }
                        // Brief pause before the next attempt instead of hammering a
                        // device that may just be mid-recovery from a marginal read.
                        delay(CAPTURE_ERROR_RETRY_DELAY_MS)
                    }
                }
            }
        } catch (e: CancellationException) {
            // Expected path when enrollment (or task removal) asks us to stop —
            // fall through to the finally block to actually release the device.
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "SCANNER_EXCEPTION in background loop: ${e.message}", e)
        } finally {
            // This finally block no longer closes the native device at all —
            // that's the whole point of the ScannerHub redesign (see its doc
            // for the full history of why repeatedly closing/reopening this
            // specific hardware was the real root cause, not any one race).
            // All that happens here is: stop OUR polling loop, hand turn-
            // taking ownership back, and let the service itself stop — the
            // physical connection stays open and ready for whoever asks next
            // (enrollment, or this same loop again on the next requestStart).
            cacheJob?.cancel()
            Log.d(TAG, "SCANNER_BACKGROUND_SCAN_STOP (session stays open)")
            scanner = null
            ScannerOwnership.release(ScannerOwnership.Owner.KIOSK)
            stopForegroundCompat()
            // See the comment on the other stopSelf(startId) call above — same
            // reasoning applies here.
            stopSelf(startId)
        }
        } // scannerLifecycleMutex.withLock
    }

    /** Swiping the app off the Recent Apps list — stop scanning entirely, per spec. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopListeningAndSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        val job = loopJob
        loopJob = null
        // cancelAndJoin (not a bare cancel()) so runLoop's own finally block —
        // running on its own coroutine, not this one — gets a chance to
        // actually release ownership/cancel its cache subscription cleanly
        // before this Service object goes away.
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(1500) { job?.cancelAndJoin() }
            }
        }
        // Fallback only, for the loopJob.cancelAndJoin() timeout case above:
        // this Service instance's own `scanner` field is just a borrowed
        // reference into ScannerHub's persistent session now, never something
        // this class owns — so there is nothing to close here. Closing it
        // would re-introduce exactly the repeated-cycling problem ScannerHub
        // exists to eliminate, and would also yank the device out from under
        // enrollment if this destroy happens to overlap with it holding
        // ownership. ScannerHub only ever closes the real connection on an
        // actual USB detach.
        scanner = null
        ScannerOwnership.release(ScannerOwnership.Owner.KIOSK)
        MembershipAudioPlayer.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Cancels the listening loop and waits (on the service's own IO scope, not
     * the caller's thread) for its finally block to actually release the
     * device before tearing down the foreground notification/service. Never
     * closes the scanner directly from here — see [runLoop]'s finally comment.
     *
     * Takes the triggering command's [startId] and passes it to stopSelf(Int)
     * rather than calling bare stopSelf(). Bare stopSelf() tears the whole
     * Service down unconditionally — including serviceScope, which would kill
     * any newer loop that had already started in response to a start request
     * that arrived while this stop's cleanup was still in flight. stopSelf(Int)
     * only actually stops the service if no newer command has been delivered
     * since [startId], so a start that raced in ahead of this cleanup finishing
     * survives instead of being destroyed the instant it begins.
     *
     * [startId] is optional: [onTaskRemoved] (the app swiped out of Recent
     * Apps) has no startId to give and, per spec, wants an unconditional stop
     * regardless of any pending start — that path passes null and falls back
     * to bare stopSelf().
     */
    private fun stopListeningAndSelf(startId: Int? = null) {
        val job = loopJob
        loopJob = null
        KioskBus.publish(null)
        if (job == null) {
            // Nothing was ever running (e.g. no scanner was connected) — nothing
            // to wait on, just tear down defensively.
            MembershipAudioPlayer.stop()
            stopForegroundCompat()
            if (startId != null) stopSelf(startId) else stopSelf()
            return
        }
        serviceScope.launch {
            job.cancelAndJoin()
            MembershipAudioPlayer.stop()
            stopForegroundCompat()
            if (startId != null) stopSelf(startId) else stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            // Two separate channels: the ongoing "still listening" notification
            // is LOW importance (silent, no heads-up) since it's just persistent
            // status, not something needing attention. The "member scanned" alert
            // stays HIGH/heads-up on its own channel. These used to share one
            // HIGH-importance channel, which meant every repost of the ongoing
            // notification (e.g. on a service restart) surfaced as a heads-up
            // alert too — the on/off "flicker" reported alongside the disabled
            // scanner. Fixing the restart storm (see runLoop) is the real fix for
            // that; this just makes the notification behavior correct regardless.
            if (manager.getNotificationChannel(ONGOING_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(ONGOING_CHANNEL_ID, "Fingerprint scanner status", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shows while the fingerprint scanner is actively listening"
                    }
                )
            }
            if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(ALERT_CHANNEL_ID, "Member scanned", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Alerts when a member is recognized while the app is backgrounded"
                    }
                )
            }
        }
    }

    private fun buildOngoingNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fingerprint scanner active")
            .setContentText("Listening for member check-ins")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /** Only fires a heads-up/full-screen alert if the app isn't already visible —
     *  when it's already in the foreground, [KioskBus] alone is enough to update
     *  the existing overlay UI. */
    private fun notifyIfBackgrounded() {
        val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (appInForeground) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val fullScreenIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Member scanned")
            .setContentText("Bringing up member details")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(ALERT_NOTIF_ID, notification)
        }
    }

    companion object {
        private const val ACTION_STOP = "com.majorgym.app.kiosk.STOP"

        /** Ask the service to try opening the scanner and, if found, start
         *  listening. Safe/cheap to call repeatedly — it no-ops if already
         *  running, and self-stops quickly if no scanner is connected. */
        fun requestStart(context: Context) {
            val intent = Intent(context, FingerprintKioskService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Used while another screen (enrollment) needs exclusive scanner access. */
        fun requestStop(context: Context) {
            context.startService(Intent(context, FingerprintKioskService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Cheap, side-effect-free check for whether a SecuGen scanner is currently
         * plugged in — reads the OS's USB device list directly, no SDK object
         * created, no permission requested. Lets the caller (MainActivity) decide
         * when it's actually worth calling [requestStart], so the service's
         * notification never flickers on/off during idle retries with nothing
         * connected. Vendor ID 4450 (0x1162) is SecuGen's — see device_filter.xml.
         */
        fun isScannerConnected(context: Context): Boolean {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
                ?: return false
            return runCatching { usbManager.deviceList.values.any { it.vendorId == 4450 } }.getOrDefault(false)
        }
    }
}
