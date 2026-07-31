package com.majorgym.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/** Builds and launches WhatsApp share messages (spec section 3, plus the renewal
 *  update add-on). */
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

    /** Tries WhatsApp first; if it isn't installed, falls back to the system
     *  share sheet (SMS / Telegram / Email / etc. — whatever the device offers). */
    fun shareText(context: Context, message: String, chooserTitle: String = "Share message") {
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
            context.startActivity(Intent.createChooser(chooserIntent, chooserTitle))
        }
    }

    fun share(context: Context, member: Member, passkey: String) =
        shareText(context, welcomeMessage(member, passkey), "Share welcome message")

    fun shareRenewal(context: Context, member: Member) =
        shareText(context, renewalMessage(member), "Share renewal update")
}
