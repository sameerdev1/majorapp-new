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
    /** Reached from the Backup screen's Backup History button: shows the
     *  lightweight date/time-only log of when backups were taken (see
     *  [com.majorgym.app.data.BackupHistoryPrefs]) - never the backup
     *  contents themselves. */
    data object BackupHistory : Screen()
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
    /** Members expired more than 2 months with no renewal (see
     *  [com.majorgym.app.data.MembershipState.HOLD]/MembershipHoldWorker).
     *  Excluded from the normal Members list/counts and from the fingerprint
     *  attendance search, but fully preserved and reachable/searchable/
     *  renewable here - renewing moves a member straight back to the normal
     *  Members list using the same Member ID (no duplication). */
    data object HoldMembers : Screen()
    /** Members whose current Due Amount (see [com.majorgym.app.data.Member.fee])
     *  is greater than zero - a payment-status filter, independent of
     *  membership status (an ACTIVE member can also be a Due member; see
     *  ProfileScreen's Due Payment section for recording a payment). */
    data object DueMembers : Screen()
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
    /** Replaces "Members" in the bottom navigation slot (Members itself is
     *  unchanged and still reachable via Dashboard -> Total Members). Shows
     *  real attendance check-ins for a selected date, backed by the new
     *  [com.majorgym.app.data.AttendanceRecord] log. */
    data object AttendanceLogs : Screen()
    /** Reached by tapping an attendance record on [AttendanceLogs]: shows
     *  that one member's recent day-by-day attendance history. */
    data class AttendanceHistory(val memberId: String) : Screen()
}
