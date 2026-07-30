package com.majorgym.app.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates member QR codes (spec section 2).
 *
 * The QR encodes ONLY the member ID — never the passkey, phone, membership
 * plan, or payment history. A future client app scans this ID and pairs it
 * with a passkey login to authenticate; anyone else who scans it learns
 * nothing beyond an opaque ID string.
 */
object QrUtils {
    fun memberQrBitmap(memberId: String, sizePx: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(memberId, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /** Static gym QR (spec section 9) — encodes only the gym ID, no personal data. */
    fun gymQrBitmap(gymId: String, sizePx: Int = 512): Bitmap = memberQrBitmap(gymId, sizePx)
}
