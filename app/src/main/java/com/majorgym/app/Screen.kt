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
    /** Shown once, immediately after a renewal is confirmed, and also reachable
     *  on-demand from the Profile screen's QR button (add-on: unique QR per
     *  add/renewal). [justRenewed] is true only when reached via an actual
     *  renewal confirmation — that's what gates the "Share Renewal Update"
     *  action, so a plain QR view/regenerate doesn't also prompt to text the
     *  member. */
    data class Renewed(val id: String, val justRenewed: Boolean = false) : Screen()
    data object Backup : Screen()
    data object Sync : Screen()
}
