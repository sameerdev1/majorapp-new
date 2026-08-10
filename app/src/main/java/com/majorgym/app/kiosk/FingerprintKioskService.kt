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

private const val TAG = "FingerprintKioskSvc"
private const val MATCHED_DISPLAY_MS = 3000L
private const val NOT_RECOGNIZED_DISPLAY_MS = 1500L
private const val LISTEN_SLICE_MS = 4000
private const val CHANNEL_ID = "fingerprint_kiosk"
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
            stopListeningAndSelf()
            return START_NOT_STICKY
        }
        // Already trying/listening — nothing new to do for a repeat start request.
        if (loopJob?.isActive == true) return START_NOT_STICKY

        loopJob = serviceScope.launch { runLoop() }
        return START_NOT_STICKY
    }

    private suspend fun CoroutineScope.runLoop() {
        // Android requires startForeground() to be called within a few seconds of
        // startForegroundService() or the OS kills the process — so this call goes
        // first, before we even know if a device is present. In practice this is a
        // non-issue: MainActivity only ever calls requestStart() after its own
        // lightweight USB device-list check already found a matching SecuGen
        // device (see isScannerConnected), so the immediate failure path below is
        // a rare defensive fallback, not the normal case.
        startForeground(ONGOING_NOTIF_ID, buildOngoingNotification())

        val fp = FingerprintScanner(this@FingerprintKioskService)
        scanner = fp

        Log.d(TAG, "SCANNER_INIT_START background loop")
        val openResult = fp.open()
        if (openResult !is FingerprintScanner.OpenResult.Success) {
            Log.w(TAG, "SCANNER_OPEN_FAILED background loop result=$openResult")
            fp.close()
            scanner = null
            stopForegroundCompat()
            stopSelf()
            return
        }
        Log.d(TAG, "SCANNER_BACKGROUND_SCAN_START")
        // Claim ownership only once the device is actually open, and release it
        // in the finally block below — no matter whether the loop exits
        // normally, hits an SDK error, or is cancelled (e.g. enrollment asked
        // us to stop). Enrollment waits on this via ScannerOwnership before it
        // ever opens its own scanner, so the two are never open concurrently.
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

            while (isActive) {
                when (val capture = fp.captureTemplate(timeoutMs = LISTEN_SLICE_MS)) {
                    is FingerprintScanner.CaptureResult.Success -> {
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
                    }
                    is FingerprintScanner.CaptureResult.Error -> {
                        // Likely unplugged mid-listen (or a genuine SDK exception,
                        // which FingerprintScanner already caught and logged under
                        // SCANNER_EXCEPTION). Stop cleanly; MainActivity's retry
                        // loop will bring the service back once reconnected.
                        Log.w(TAG, "SCANNER_CAPTURE_FAILED background loop, stopping")
                        break
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
            // This finally block is the ONLY place the background scanner is ever
            // closed, and it always runs on this loop's own coroutine — never
            // from onStartCommand/onDestroy's calling thread. That was the root
            // cause of the enrollment crash: the service used to close the
            // native device directly from the command-handling thread while this
            // loop might still be mid-capture on the IO dispatcher, i.e. two
            // threads touching the same native SecuGen handle at once.
            //
            // closeAndAwait() (not close()) is deliberate here: this suspends
            // until the native device is ACTUALLY released before we announce
            // ownership as free. The old fire-and-forget close() returned
            // instantly while the real release kept running in the background —
            // so ScannerOwnership.release() below used to fire while the device
            // was still physically open, letting enrollment race in and open
            // its own connection against a device the kiosk hadn't actually let
            // go of yet. That race, not any navigation-timing issue, was the
            // real source of "Fingerprint scanner unavailable" / crashes.
            cacheJob?.cancel()
            fp.closeAndAwait()
            Log.d(TAG, "SCANNER_BACKGROUND_SCAN_STOP")
            scanner = null
            ScannerOwnership.release(ScannerOwnership.Owner.KIOSK)
            stopForegroundCompat()
            stopSelf()
        }
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
        // actually close the device before this Service object goes away.
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(1500) { job?.cancelAndJoin() }
            }
        }
        // Fallback only: if the loop never started (no scanner was ever open)
        // or the join above timed out, make sure nothing is left dangling.
        runCatching { scanner?.close() }
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
     */
    private fun stopListeningAndSelf() {
        val job = loopJob
        loopJob = null
        KioskBus.publish(null)
        if (job == null) {
            // Nothing was ever running (e.g. no scanner was connected) — nothing
            // to wait on, just tear down defensively.
            MembershipAudioPlayer.stop()
            stopForegroundCompat()
            stopSelf()
            return
        }
        serviceScope.launch {
            job.cancelAndJoin()
            MembershipAudioPlayer.stop()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Fingerprint scanner", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Shows while the fingerprint scanner is actively listening"
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
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
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
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
