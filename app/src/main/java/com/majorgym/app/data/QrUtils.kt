package com.majorgym.app.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID

/**
 * Generates member QR codes (spec section 2).
 *
 * The QR encodes the member ID plus a rotating, time-limited [Member.qrToken] —
 * never the passkey, phone, membership plan, or payment history. A future client
 * app scans this pair and validates the token against the member record (id
 * matches, token matches, not expired) before treating the membership data as
 * current. Anyone else who scans it learns nothing beyond an opaque id/token pair.
 *
 * Add-on (unique, time-limited membership QR): the token is regenerated every
 * time membership data changes in a way the client needs to re-sync — new
 * registration, every renewal, and manual "Regenerate QR" — with a fresh expiry
 * window. This means:
 *   - A QR printed/screenshotted before a renewal stops validating once its
 *     window lapses, so it can never be replayed to read stale membership data.
 *   - Only the most recently generated QR for a member is ever valid.
 */
object QrUtils {
    /** Default window a freshly generated membership QR stays valid for. */
    const val TOKEN_VALIDITY_MILLIS: Long = 48L * 60 * 60 * 1000 // 48 hours

    /** Separator between member id and token inside the QR payload. Must never
     *  appear inside a member id (ids are UUIDs, so this is always safe). */
    private const val SEPARATOR = "|"

    /** Generates a fresh, unpredictable token to embed in a member's QR. */
    fun freshToken(): String = UUID.randomUUID().toString()

    /** The QR payload for a member: their id plus their current rotating token. */
    fun payload(memberId: String, token: String): String = "$memberId$SEPARATOR$token"

    /** Whether [member]'s current token has not yet expired. */
    fun isTokenValid(member: Member, nowMillis: Long = System.currentTimeMillis()): Boolean =
        member.qrToken.isNotEmpty() && nowMillis < member.qrTokenExpiryMillis

    /** Whether a QR payload scanned by a client matches [member]'s current, unexpired token. */
    fun validate(member: Member, scannedPayload: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val parts = scannedPayload.split(SEPARATOR, limit = 2)
        if (parts.size != 2) return false
        val (scannedId, scannedToken) = parts
        return scannedId == member.id && scannedToken == member.qrToken && isTokenValid(member, nowMillis)
    }

    fun memberQrBitmap(memberId: String, token: String, sizePx: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(payload(memberId, token), BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /** Static gym-wide attendance QR (spec section 9) — a single fixed string for the
     *  whole gym, never rotated, unlike the per-member QR above. Encodes only the gym
     *  ID/attendance code, no personal data. */
    fun gymQrBitmap(gymId: String, sizePx: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(gymId, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
