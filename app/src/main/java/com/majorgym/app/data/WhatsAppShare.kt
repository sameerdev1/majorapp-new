package com.majorgym.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Builds and launches WhatsApp share messages (spec section 3, plus the renewal
 *  update add-on). */
object WhatsAppShare {

    /** Client App download link appended to every new-member WhatsApp receipt
     *  (Part 4) - must stay exactly this URL. */
    private const val CLIENT_APP_LINK = "https://drive.google.com/file/d/17vqZ6Y95e1GzWlVwE8v6aQDtTQmOeXd0/view?usp=sharing"

    fun welcomeMessage(member: Member, passkey: String): String = buildString {
        appendLine("\uD83C\uDFCB\uFE0F MAJOR GYM")
        appendLine("\uD83C\uDF89 Welcome! Your membership is now active.")
        appendLine()
        appendLine("\uD83D\uDCCB Membership Details")
        appendLine("Plan: ${member.plan}")
        appendLine("Start: ${formatDate(member.joinedMillis)}")
        appendLine("Expiry: ${formatDate(member.expiryMillis)}")
        appendLine("Phone: ${member.phone}")
        // Fix #3: the Passkey must no longer be shown anywhere owner-facing,
        // including this WhatsApp receipt - it's kept out of the message
        // text entirely now. Passkey generation/hashing/storage themselves
        // are untouched (see PasskeyUtils/Member.passwordHash); [passkey] is
        // still accepted here only so this signature doesn't need to change
        // everywhere it's called from.
        appendLine()
        appendLine("\uD83D\uDCF1 Client App")
        appendLine("Download the Major Gym Client App:")
        appendLine("\uD83D\uDC49 $CLIENT_APP_LINK")
    }

    /**
     * Sent right after a renewal is confirmed. Includes the new expiry, plan,
     * and days remaining so the member has everything at a glance without
     * needing to open the client app.
     */
    fun renewalMessage(member: Member): String = buildString {
        appendLine("Hi ${member.name}, your Major Gym membership has been renewed \u2705")
        appendLine()
        appendLine("Plan: ${member.plan}")
        appendLine("Fee Paid: ${formatMoney(member.fee)}")
        appendLine("New Expiry Date: ${formatDate(member.expiryMillis)}")
        val days = daysBetweenNow(member.expiryMillis)
        if (days >= 0) appendLine("Days Remaining: $days")
        appendLine()
        appendLine("Thanks for staying with us \u2014 see you at the gym!")
    }

    /**
     * Normalizes a member's stored phone number into the digits-with-country-code
     * form WhatsApp's deep link expects (e.g. "9876543210" -> "919876543210").
     * [Member.phone] is always saved as a plain 10-digit Indian mobile number (see
     * AddEditMemberScreen, which strips everything but digits and caps it at 10),
     * but this also tolerates a number that already carries a "91"/"+91" prefix
     * so nothing breaks if that ever changes.
     */
    private fun whatsAppNumber(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "91$digits"
            digits.length == 12 && digits.startsWith("91") -> digits
            else -> digits
        }
    }

    /** Opens WhatsApp directly on the chat for [phone] with [message] already
     *  filled into the composer — the staff still has to tap WhatsApp's own
     *  Send button, nothing is sent automatically. Falls back to the system
     *  share sheet (SMS / Telegram / Email / etc.) if WhatsApp isn't installed
     *  and no browser can hand off the wa.me link either. */
    fun shareText(context: Context, phone: String, message: String, chooserTitle: String = "Share message") {
        val uri = Uri.parse("https://wa.me/${whatsAppNumber(phone)}?text=${Uri.encode(message)}")

        val whatsappIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
        }
        try {
            context.startActivity(whatsappIntent)
            return
        } catch (e: ActivityNotFoundException) {
            // Fall through — WhatsApp isn't installed under that package name.
        }

        try {
            // No package restriction: lets a browser resolve the wa.me link,
            // which itself hands off to WhatsApp (or WhatsApp Business).
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            return
        } catch (e: ActivityNotFoundException) {
            // Fall through to the generic share sheet below.
        }

        val chooserIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        context.startActivity(Intent.createChooser(chooserIntent, chooserTitle))
    }

    fun share(context: Context, member: Member, passkey: String) =
        shareText(context, member.phone, welcomeMessage(member, passkey), "Share welcome message")

    fun shareRenewal(context: Context, member: Member) =
        shareText(context, member.phone, renewalMessage(member), "Share renewal update")
}
