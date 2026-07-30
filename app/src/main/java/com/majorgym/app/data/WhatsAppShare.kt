package com.majorgym.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Builds and launches the post-registration share (spec section 3). */
object WhatsAppShare {

    fun welcomeMessage(member: Member, passkey: String): String = buildString {
        appendLine("Thank you for joining Major Gym.")
        appendLine()
        appendLine("Membership: ${member.plan}")
        appendLine("Start Date: ${formatDate(member.joinedMillis)}")
        appendLine("Expiry Date: ${formatDate(member.expiryMillis)}")
        appendLine("Phone Number: ${member.phone}")
        appendLine("Temporary Passkey: $passkey")
        appendLine()
        appendLine("Install the Major Gym Client App.")
        appendLine("Welcome to Major Gym.")
    }

    /**
     * Tries WhatsApp first; if it isn't installed, falls back to the system
     * share sheet (SMS / Telegram / Email / etc. — whatever the device offers).
     */
    fun share(context: Context, member: Member, passkey: String) {
        val message = welcomeMessage(member, passkey)
        val whatsappIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage("com.whatsapp")
        }
        try {
            context.startActivity(whatsappIntent)
        } catch (e: ActivityNotFoundException) {
            val chooserIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            }
            context.startActivity(Intent.createChooser(chooserIntent, "Share welcome message"))
        }
    }
}
