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
    /** Reached by tapping the Dashboard's four stat cards. Each just filters
     *  the same [com.majorgym.app.data.Member] list / statusOf logic the
     *  Dashboard already uses to compute those same four numbers — so a tap
     *  always shows exactly the members counted in that card, with zero risk
     *  of the list and the count ever disagreeing. */
    data object TotalMembers : Screen()
    data object ActiveMembers : Screen()
    data object ExpiringMembers : Screen()
    data object ExpiredMembers : Screen()
    /** The gym's fixed check-in QR (display/share only — there is no in-app
     *  camera scanner). Reached via the Dashboard's "Open Attendance
     *  Scanner" button instead of being permanently shown on the Dashboard.
     *  Renders the same [com.majorgym.app.ui.GymAttendanceQrCard] as before —
     *  no duplicate QR/attendance implementation was created. */
    data object Attendance : Screen()
    /** Enroll/re-enroll a member's fingerprint on the connected SecuGen USB
     *  scanner. Reachable from Profile. [returnTo] is where "Done"/"Back" goes —
     *  Registered right after sign-up, or Profile when re-enrolling later. */
    data class EnrollFingerprint(val id: String, val returnTo: Screen = Profile(id)) : Screen()
}
