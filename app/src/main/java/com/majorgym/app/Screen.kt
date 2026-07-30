package com.majorgym.app

sealed class Screen {
    data object Dashboard : Screen()
    data object Members : Screen()
    data object Add : Screen()
    data class Edit(val id: String) : Screen()
    /** Shown once, immediately after a new member is saved: displays their QR and
     *  the WhatsApp share action (spec sections 2-3). Not reachable by editing. */
    data class Registered(val id: String, val passkey: String) : Screen()
    data class Profile(val id: String) : Screen()
    data class Renew(val id: String) : Screen()
    data object Backup : Screen()
    data object Sync : Screen()
}
