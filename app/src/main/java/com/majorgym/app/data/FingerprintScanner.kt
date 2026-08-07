package com.majorgym.app.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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

/**
 * Thin coroutine-friendly wrapper around SecuGen's FDx SDK Pro (JSGFPLib), for a
 * USB fingerprint scanner (Hamster Pro/Air/IV/Plus series) plugged into the
 * front-desk device via USB-OTG.
 *
 * Usage from a screen:
 *   val scanner = remember { FingerprintScanner(activity) }
 *   DisposableEffect(Unit) { onDispose { scanner.close() } }
 *   scanner.open()                 // finds device, requests USB permission if needed
 *   scanner.captureTemplate()      // one finger placement -> ISO 19794-2 template bytes
 *   scanner.match(t1, t2)          // compare two templates
 *
 * Only one [FingerprintScanner] should have the device open at a time.
 */
class FingerprintScanner(private val activity: ComponentActivity) {

    sealed class OpenResult {
        data object Success : OpenResult()
        data object DeviceNotFound : OpenResult()
        data object PermissionDenied : OpenResult()
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
     * Safe to call when no scanner is plugged in: this simply returns
     * [OpenResult.DeviceNotFound] rather than throwing, and never leaves
     * [sgfplib]/[initialized] in a state that would make [close] unsafe.
     */
    suspend fun open(): OpenResult = withContext(Dispatchers.IO) {
        val lib = runCatching {
            JSGFPLib(activity, activity.getSystemService(Context.USB_SERVICE) as UsbManager)
        }.getOrNull() ?: return@withContext OpenResult.DeviceNotFound
        sgfplib = lib

        val initError = runCatching { lib.Init(SGFDxDeviceName.SG_DEV_AUTO) }.getOrNull()
        if (initError == null || initError != SGFDxErrorCode.SGFDX_ERROR_NONE) {
            // Init failed (or threw) — nothing further was allocated on the native
            // side worth releasing, so leave initialized=false and bail out cleanly.
            return@withContext OpenResult.DeviceNotFound
        }
        initialized = true

        val usbDevice: UsbDevice = lib.GetUsbDevice() ?: return@withContext OpenResult.DeviceNotFound
        val usbManager = lib.GetUsbManager()

        if (!usbManager.hasPermission(usbDevice)) {
            val granted = requestUsbPermission(usbDevice, usbManager)
            if (!granted) return@withContext OpenResult.PermissionDenied
        }

        val openError = runCatching { lib.OpenDevice(0L) }.getOrNull()
        if (openError == null || openError != SGFDxErrorCode.SGFDX_ERROR_NONE) {
            return@withContext OpenResult.Error(openError ?: -1L)
        }
        deviceOpened = true

        val deviceInfo = SGDeviceInfoParam()
        lib.GetDeviceInfo(deviceInfo)
        imageWidth = deviceInfo.imageWidth
        imageHeight = deviceInfo.imageHeight

        lib.SetTemplateFormat(SGFDxTemplateFormat.TEMPLATE_FORMAT_ISO19794)
        lib.GetMaxTemplateSize(maxTemplateSize)

        OpenResult.Success
    }

    private suspend fun requestUsbPermission(device: UsbDevice, usbManager: UsbManager): Boolean =
        suspendCancellableCoroutine { cont ->
            if (!receiverRegistered) {
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= 33) {
                    activity.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    activity.registerReceiver(usbReceiver, filter)
                }
                receiverRegistered = true
            }
            pendingPermission = { granted -> if (cont.isActive) cont.resume(granted) }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                activity, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(device, permissionIntent)
            cont.invokeOnCancellation { pendingPermission = null }
        }

    /**
     * Blocks (on a background thread) until a finger is placed on the sensor or
     * [timeoutMs] elapses, then builds an ISO 19794-2 template from the scan.
     * Call [open] first.
     */
    suspend fun captureTemplate(timeoutMs: Int = 10000, minQuality: Int = 50): CaptureResult =
        withContext(Dispatchers.IO) {
            val lib = sgfplib
            if (lib == null || !deviceOpened || imageWidth == 0 || imageHeight == 0) {
                return@withContext CaptureResult.Error(-1)
            }
            val image = ByteArray(imageWidth * imageHeight)
            val captureError = lib.GetImageEx(image, timeoutMs.toLong(), minQuality.toLong())
            if (captureError == SGFDxErrorCode.SGFDX_ERROR_TIME_OUT) {
                return@withContext CaptureResult.Timeout
            }
            if (captureError != SGFDxErrorCode.SGFDX_ERROR_NONE) {
                return@withContext CaptureResult.Error(captureError)
            }

            val quality = intArrayOf(0)
            lib.GetImageQuality(imageWidth.toLong(), imageHeight.toLong(), image, quality)

            val fpInfo = SGFingerInfo().apply {
                FingerNumber = 1
                ImageQuality = quality[0]
                ImpressionType = SGImpressionType.SG_IMPTYPE_LP
                ViewNumber = 1
            }
            val template = ByteArray(maxTemplateSize[0])
            val templateError = lib.CreateTemplate(fpInfo, image, template)
            if (templateError != SGFDxErrorCode.SGFDX_ERROR_NONE) {
                return@withContext CaptureResult.Error(templateError)
            }

            val size = intArrayOf(0)
            lib.GetTemplateSize(template, size)
            val trimmed = if (size[0] in 1 until template.size) template.copyOf(size[0]) else template
            CaptureResult.Success(trimmed, quality[0])
        }

    /** Compares two ISO 19794-2 templates (e.g. a freshly captured scan against a
     *  member's stored [Member.fingerprintTemplate]) at normal security level.
     *  Returns false (never throws) if the scanner was never successfully opened. */
    suspend fun match(template1: ByteArray, template2: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val lib = sgfplib ?: return@withContext false
        val matched = BooleanArray(1)
        runCatching { lib.MatchTemplate(template1, template2, SGFDxSecurityLevel.SL_NORMAL, matched) }
        matched[0]
    }

    /**
     * Releases the device and unregisters the USB permission receiver, if either
     * was ever actually acquired. Safe to call multiple times, safe to call when
     * a scanner was never found/opened (e.g. the user backed out of the enroll
     * screen before any device connected), and never throws — always call this
     * when leaving the enroll/check-in screen.
     */
    fun close() {
        val lib = sgfplib
        if (lib != null) {
            if (deviceOpened) {
                runCatching { lib.CloseDevice() }
                deviceOpened = false
            }
            if (initialized) {
                runCatching { lib.Close() }
                initialized = false
            }
        }
        sgfplib = null
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.majorgym.app.USB_PERMISSION"
    }
}
