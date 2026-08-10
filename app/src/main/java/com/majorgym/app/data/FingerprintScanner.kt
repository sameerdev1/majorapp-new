package com.majorgym.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

import SecuGen.FDxSDKPro.JSGFPLib
import SecuGen.FDxSDKPro.SGDeviceInfoParam
import SecuGen.FDxSDKPro.SGFDxDeviceName
import SecuGen.FDxSDKPro.SGFDxErrorCode
import SecuGen.FDxSDKPro.SGFDxSecurityLevel
import SecuGen.FDxSDKPro.SGFDxTemplateFormat
import SecuGen.FDxSDKPro.SGFingerInfo
import SecuGen.FDxSDKPro.SGImpressionType

private const val TAG = "FingerprintScanner"

/**
 * Thin coroutine-friendly wrapper around SecuGen's FDx SDK Pro (JSGFPLib), for a
 * USB fingerprint scanner (Hamster Pro/Air/IV/Plus series) plugged into the
 * front-desk device via USB-OTG.
 *
 * Takes a plain [Context] rather than an Activity, so either an Activity (e.g.
 * during enrollment) or a Service (the kiosk background listener) can own it.
 *
 * Usage:
 *   val scanner = remember { FingerprintScanner(context) }
 *   DisposableEffect(Unit) { onDispose { scanner.close() } }
 *   scanner.open()                 // finds device, requests USB permission if needed
 *   scanner.captureTemplate()      // one finger placement -> ISO 19794-2 template bytes
 *   scanner.match(t1, t2)          // compare two templates
 *
 * Only one [FingerprintScanner] should have the device open at a time — see
 * [com.majorgym.app.kiosk.ScannerOwnership] for how callers coordinate that
 * across the background kiosk service and the enrollment screen.
 *
 * All native SDK entry points are (a) wrapped in try/catch so an SDK-level
 * exception can never crash the caller, and (b) serialized behind [callMutex]
 * so this *instance* never has two native calls in flight on different
 * threads at once (e.g. a capture in progress on the IO dispatcher while
 * [close] is invoked from the UI thread during teardown).
 */
class FingerprintScanner(private val appContext: Context) {

    sealed class OpenResult {
        data object Success : OpenResult()
        data object DeviceNotFound : OpenResult()
        data object PermissionDenied : OpenResult()
        data object Busy : OpenResult()
        data class Error(val code: Long) : OpenResult()
    }

    sealed class CaptureResult {
        data class Success(val template: ByteArray, val imageQuality: Int) : CaptureResult()
        data object Timeout : CaptureResult()
        data class Error(val code: Long) : CaptureResult()
    }

    private var sgfplib: JSGFPLib? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var maxTemplateSize = intArrayOf(400)
    /** True only once Init() has actually succeeded on [sgfplib] — distinct from
     *  [deviceOpened], and what guards whether it's safe to call native Close(). */
    private var initialized = false
    private var deviceOpened = false
    private var receiverRegistered = false

    private var pendingPermission: ((Boolean) -> Unit)? = null

    /** Serializes every native call this instance makes (open/capture/match/close)
     *  so two of them never touch the SecuGen object from different threads at
     *  the same time. This is what makes [close] safe to call while a capture
     *  is in flight — the close simply waits its turn instead of racing it. */
    private val callMutex = Mutex()

    /** Dedicated scope for [close], which must be safely callable synchronously
     *  (e.g. from a Compose DisposableEffect) even while a suspend capture call
     *  is mid-flight and holding [callMutex]. */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                pendingPermission?.invoke(granted)
                pendingPermission = null
            }
        }
    }

    /**
     * Initializes the SDK, finds an attached SecuGen device, requests USB
     * permission if not already granted (suspends until the user answers the
     * system dialog), and opens the device ready for capture.
     *
     * Safe to call when no scanner is plugged in, when the device is already
     * claimed by another owner, or if the SDK throws instead of returning an
     * error code: this always returns a result, never throws, and never
     * leaves [sgfplib]/[initialized] in a state that would make [close] unsafe.
     */
    suspend fun open(): OpenResult = withContext(Dispatchers.IO) {
        callMutex.withLock {
            // Defensive cleanup: if this instance still has a handle open from a
            // previous open() call (a caller re-opening for a second scan instead
            // of reusing the session, or any other misuse), release it fully
            // before touching the SDK again. Without this, the second open()
            // tries to Init()/OpenDevice() the same physical USB device on top
            // of a handle that's still live — which doesn't fail cleanly, it can
            // crash the native SDK outright and take the whole app down with it.
            if (sgfplib != null) {
                Log.w(TAG, "SCANNER_REOPEN stale handle found on open() — releasing it first")
                val stale = sgfplib
                if (deviceOpened) {
                    runCatching { stale?.CloseDevice() }
                        .onFailure { Log.e(TAG, "SCANNER_EXCEPTION closing stale device: ${it.message}", it) }
                    deviceOpened = false
                }
                if (initialized) {
                    runCatching { stale?.Close() }
                        .onFailure { Log.e(TAG, "SCANNER_EXCEPTION closing stale handle: ${it.message}", it) }
                    initialized = false
                }
                sgfplib = null
            }

            Log.d(TAG, "SCANNER_INIT_START")
            val lib = runCatching {
                JSGFPLib(appContext, appContext.getSystemService(Context.USB_SERVICE) as UsbManager)
            }.getOrElse { e ->
                Log.e(TAG, "SCANNER_INIT_FAILED constructing JSGFPLib: ${e.message}", e)
                return@withContext OpenResult.DeviceNotFound
            }
            sgfplib = lib

            val initResult = runCatching { lib.Init(SGFDxDeviceName.SG_DEV_AUTO) }
            val initError = initResult.getOrNull()
            if (initResult.isFailure) {
                Log.e(TAG, "SCANNER_INIT_FAILED exception: ${initResult.exceptionOrNull()?.message}", initResult.exceptionOrNull())
                sgfplib = null
                return@withContext OpenResult.DeviceNotFound
            }
            if (initError == null || initError != SGFDxErrorCode.SGFDX_ERROR_NONE) {
                // Init failed — nothing further was allocated on the native side
                // worth releasing, so leave initialized=false and bail out cleanly.
                Log.w(TAG, "SCANNER_INIT_FAILED code=$initError")
                sgfplib = null
                return@withContext OpenResult.DeviceNotFound
            }
            initialized = true
            Log.d(TAG, "SCANNER_INIT_SUCCESS")

            val usbDevice: UsbDevice = runCatching { lib.GetUsbDevice() }.getOrNull()
                ?: return@withContext OpenResult.DeviceNotFound
            val usbManager = runCatching { lib.GetUsbManager() }.getOrNull()
                ?: return@withContext OpenResult.DeviceNotFound

            if (!usbManager.hasPermission(usbDevice)) {
                val granted = requestUsbPermission(usbDevice, usbManager)
                if (!granted) return@withContext OpenResult.PermissionDenied
            }

            Log.d(TAG, "SCANNER_OPEN")
            val openResult = runCatching { lib.OpenDevice(0L) }
            val openError = openResult.getOrNull()
            if (openResult.isFailure) {
                Log.e(TAG, "SCANNER_OPEN_FAILED exception: ${openResult.exceptionOrNull()?.message}", openResult.exceptionOrNull())
                return@withContext OpenResult.Busy
            }
            if (openError == null || openError != SGFDxErrorCode.SGFDX_ERROR_NONE) {
                Log.w(TAG, "SCANNER_OPEN_FAILED code=$openError")
                // A nonzero code here (rather than a thrown exception) most often
                // means the device is already claimed by another open handle —
                // e.g. the kiosk background service didn't release it in time.
                return@withContext OpenResult.Busy
            }
            deviceOpened = true

            val deviceInfoResult = runCatching {
                val deviceInfo = SGDeviceInfoParam()
                lib.GetDeviceInfo(deviceInfo)
                imageWidth = deviceInfo.imageWidth
                imageHeight = deviceInfo.imageHeight
                lib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_ISO19794)
                lib.GetMaxTemplateSize(maxTemplateSize)
            }
            if (deviceInfoResult.isFailure) {
                Log.e(TAG, "SCANNER_EXCEPTION reading device info: ${deviceInfoResult.exceptionOrNull()?.message}", deviceInfoResult.exceptionOrNull())
                return@withContext OpenResult.Error(-1)
            }

            OpenResult.Success
        }
    }

    private suspend fun requestUsbPermission(device: UsbDevice, usbManager: UsbManager): Boolean =
        suspendCancellableCoroutine { cont ->
            if (!receiverRegistered) {
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        appContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
                    } else {
                        appContext.registerReceiver(usbReceiver, filter)
                    }
                    receiverRegistered = true
                }
            }
            pendingPermission = { granted -> if (cont.isActive) cont.resume(granted) }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                appContext, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            runCatching { usbManager.requestPermission(device, permissionIntent) }
                .onFailure { if (cont.isActive) cont.resume(false) }
            cont.invokeOnCancellation { pendingPermission = null }
        }

    /**
     * Blocks (on a background thread) until a finger is placed on the sensor or
     * [timeoutMs] elapses, then builds an ISO 19794-2 template from the scan.
     * Call [open] first. Never throws — any SDK-level exception during capture,
     * quality scoring, or template creation/sizing comes back as
     * [CaptureResult.Error] instead of propagating up and crashing the caller.
     */
    suspend fun captureTemplate(timeoutMs: Int = 10000, minQuality: Int = 50): CaptureResult =
        withContext(Dispatchers.IO) {
            callMutex.withLock {
                val lib = sgfplib
                if (lib == null || !deviceOpened || imageWidth == 0 || imageHeight == 0) {
                    Log.w(TAG, "SCANNER_CAPTURE_FAILED not open (lib=${lib != null}, deviceOpened=$deviceOpened)")
                    return@withContext CaptureResult.Error(-1)
                }

                val image = ByteArray(imageWidth * imageHeight)
                val captureResult = runCatching { lib.GetImageEx(image, timeoutMs.toLong(), minQuality.toLong()) }
                if (captureResult.isFailure) {
                    Log.e(TAG, "SCANNER_EXCEPTION during GetImageEx: ${captureResult.exceptionOrNull()?.message}", captureResult.exceptionOrNull())
                    return@withContext CaptureResult.Error(-1)
                }
                val captureError = captureResult.getOrThrow()
                if (captureError == SGFDxErrorCode.SGFDX_ERROR_TIME_OUT) {
                    return@withContext CaptureResult.Timeout
                }
                if (captureError != SGFDxErrorCode.SGFDX_ERROR_NONE) {
                    Log.w(TAG, "SCANNER_CAPTURE_FAILED code=$captureError")
                    return@withContext CaptureResult.Error(captureError)
                }
                Log.d(TAG, "SCANNER_FINGER_DETECTED")

                val quality = intArrayOf(0)
                runCatching { lib.GetImageQuality(imageWidth.toLong(), imageHeight.toLong(), image, quality) }
                    .onFailure { Log.e(TAG, "SCANNER_EXCEPTION during GetImageQuality: ${it.message}", it) }

                val fpInfo = SGFingerInfo().apply {
                    FingerNumber = 1
                    ImageQuality = quality[0]
                    ImpressionType = SGImpressionType.SG_IMPTYPE_LP
                    ViewNumber = 1
                }
                val template = ByteArray(maxTemplateSize[0])
                val templateResult = runCatching { lib.CreateTemplate(fpInfo, image, template) }
                if (templateResult.isFailure) {
                    Log.e(TAG, "SCANNER_EXCEPTION during CreateTemplate: ${templateResult.exceptionOrNull()?.message}", templateResult.exceptionOrNull())
                    return@withContext CaptureResult.Error(-1)
                }
                val templateError = templateResult.getOrThrow()
                if (templateError != SGFDxErrorCode.SGFDX_ERROR_NONE) {
                    Log.w(TAG, "SCANNER_TEMPLATE_FAILED code=$templateError")
                    return@withContext CaptureResult.Error(templateError)
                }

                val size = intArrayOf(0)
                runCatching { lib.GetTemplateSize(template, size) }
                    .onFailure { Log.e(TAG, "SCANNER_EXCEPTION during GetTemplateSize: ${it.message}", it) }
                val trimmed = if (size[0] in 1 until template.size) template.copyOf(size[0]) else template
                Log.d(TAG, "SCANNER_CAPTURE_SUCCESS quality=${quality[0]}")
                CaptureResult.Success(trimmed, quality[0])
            }
        }

    /** Compares two ISO 19794-2 templates (e.g. a freshly captured scan against a
     *  member's stored [Member.fingerprintTemplate]) at normal security level.
     *  Returns false (never throws) if the scanner was never successfully opened
     *  or the SDK throws during the compare. */
    suspend fun match(template1: ByteArray, template2: ByteArray): Boolean = withContext(Dispatchers.IO) {
        callMutex.withLock {
            val lib = sgfplib ?: return@withContext false
            val matched = BooleanArray(1)
            runCatching { lib.MatchTemplate(template1, template2, SGFDxSecurityLevel.SL_NORMAL, matched) }
                .onFailure { Log.e(TAG, "SCANNER_EXCEPTION during MatchTemplate: ${it.message}", it) }
            matched[0]
        }
    }

    /**
     * Releases the device and unregisters the USB permission receiver, if either
     * was ever actually acquired. Safe to call multiple times, safe to call when
     * a scanner was never found/opened, and never throws.
     *
     * This is intentionally async internally (dispatched on [closeScope], which
     * waits for [callMutex]) rather than closing the native handle immediately
     * inline: callers like Compose's `onDispose` invoke this synchronously, and
     * a capture may still be in flight on the IO dispatcher at that moment.
     * Closing the native object out from under an in-progress native call on
     * another thread is exactly the kind of cross-thread access that was
     * crashing enrollment — this makes close() wait its turn instead.
     *
     * IMPORTANT: this returns before the native device is actually released —
     * it only guarantees the release will *eventually* happen. A caller that
     * needs to know the device is truly free before doing anything else (e.g.
     * announcing ownership released so a different owner can open it) must use
     * [closeAndAwait] instead — see that function's doc for why this distinction
     * is exactly what was still causing "scanner unavailable"/crashes even
     * after enrollment started pausing the background scanner earlier.
     */
    fun close() {
        closeScope.launch {
            releaseNow()
            closeScope.cancel()
        }
    }

    /**
     * Same release logic as [close], but suspends until the native device is
     * actually closed instead of firing it off in the background.
     *
     * Use this from any caller that is already in a coroutine and needs a real
     * guarantee the physical USB device is free before proceeding — in
     * particular [com.majorgym.app.kiosk.FingerprintKioskService], which used
     * to call [close] (fire-and-forget) and then immediately mark
     * [com.majorgym.app.kiosk.ScannerOwnership] as released on the very next
     * line. That released the "ownership" flag before the real native
     * CloseDevice()/Close() calls had actually finished, so the enrollment
     * screen could see "released" and try to open its own connection while the
     * kiosk's close was still physically in progress — a genuine race for the
     * same USB device, which is what was producing "Fingerprint scanner
     * unavailable" (and, occasionally, a native-level crash) regardless of how
     * early enrollment asked the background scanner to stop.
     */
    suspend fun closeAndAwait() {
        releaseNow()
    }

    private suspend fun releaseNow() {
        callMutex.withLock {
            val lib = sgfplib
            if (lib != null) {
                if (deviceOpened) {
                    runCatching { lib.CloseDevice() }
                        .onFailure { Log.e(TAG, "SCANNER_EXCEPTION during CloseDevice: ${it.message}", it) }
                    deviceOpened = false
                }
                if (initialized) {
                    runCatching { lib.Close() }
                        .onFailure { Log.e(TAG, "SCANNER_EXCEPTION during Close: ${it.message}", it) }
                    initialized = false
                }
            }
            sgfplib = null
            if (receiverRegistered) {
                runCatching { appContext.unregisterReceiver(usbReceiver) }
                receiverRegistered = false
            }
            Log.d(TAG, "SCANNER_RELEASE")
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.majorgym.app.USB_PERMISSION"
    }
}
