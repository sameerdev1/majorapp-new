package com.majorgym.app.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.util.UUID

/**
 * Generates the two QR codes this app hands out (spec sections 1 and 2).
 *
 * 1. The **member QR** carries a full, compact JSON snapshot of that member's
 *    own record — id, name, phone, plan, expiry, passkey hash, current
 *    rotating token — and nothing else (no other members' data). The
 *    MajorGym Client App (Flutter) scans this ONCE, at first login and again
 *    after every renewal, to populate its local SQLite cache with no network
 *    or cloud backend involved. That's also why it only needs to be scanned
 *    at those two moments: those are the only times this data actually
 *    changes.
 * 2. The **gym attendance QR** ([GYM_ATTENDANCE_CODE]) is one fixed string
 *    for the whole gym, scanned by members every visit to check in.
 *
 * Add-on (unique, time-limited membership QR): [Member.qrToken] rotates on
 * every registration, renewal, and manual "Regenerate QR", each with a fresh
 * expiry window — so a QR captured before a renewal stops working once its
 * window lapses, and can never be replayed to hand out stale membership data.
 */
object QrUtils {
    /** Static attendance QR content (spec section 1). One fixed value for the whole
     *  gym — never regenerated, never rotated, unlike the per-member QR below. Every
     *  member/client scans this exact same code to check in; this app only needs to
     *  render it and let the owner share/display it. */
    const val GYM_ATTENDANCE_CODE = "MAJOR_GYM_ATTENDANCE_2026"

    /** Shown inside the member's onboarding payload; the client app displays this
     *  as-is rather than hardcoding its own copy. Single gym for v1. */
    const val GYM_NAME = "MAJOR GYM"

    /** Default window a freshly generated membership QR stays valid for. */
    const val TOKEN_VALIDITY_MILLIS: Long = 48L * 60 * 60 * 1000 // 48 hours

    /** Generates a fresh, unpredictable token to embed in a member's QR. */
    fun freshToken(): String = UUID.randomUUID().toString()

    /** Whether [member]'s current token has not yet expired. */
    fun isTokenValid(member: Member, nowMillis: Long = System.currentTimeMillis()): Boolean =
        member.qrToken.isNotEmpty() && nowMillis < member.qrTokenExpiryMillis

    /**
     * The member QR's content: a compact JSON object with exactly what the
     * client app needs to onboard/refresh itself, and nothing more (no other
     * members' data, no full payment history — just this one record's
     * current-state fields). Field names match what the Flutter client's
     * QrPayloadParser expects; keep the two in sync if either side changes.
     */
    fun onboardingPayload(member: Member): String {
        val o = JSONObject()
        o.put("id", member.id)
        o.put("name", member.name)
        o.put("phone", member.phone)
        o.put("plan", member.plan)
        o.put("fee", member.fee)
        o.put("joinedMillis", member.joinedMillis)
        o.put("expiryMillis", member.expiryMillis)
        o.put("passwordHash", member.passwordHash)
        o.put("token", member.qrToken)
        o.put("tokenExpiryMillis", member.qrTokenExpiryMillis)
        o.put("gymName", GYM_NAME)
        // Renewal/registration history — lets the client show "Last Payment Date"
        // on its Membership page without a separate data channel.
        o.put("historyJson", member.historyJson)
        return o.toString()
    }

    fun memberQrBitmap(member: Member, sizePx: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(onboardingPayload(member), BarcodeFormat.QR_CODE, sizePx, sizePx)
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
