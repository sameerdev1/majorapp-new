package com.majorgym.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Values for [Member.membershipState] (Hold Members feature).
 *  ACTIVE: shown in the normal Members list, fingerprint search, dashboard counts.
 *  HOLD: expired 2+ months with no renewal - preserved in full, but hidden from
 *  the normal Members list/counts and excluded from the fingerprint attendance
 *  search (see FingerprintKioskService). Renewing a Hold member (RenewScreen)
 *  moves them back to ACTIVE immediately using the same Member ID - no
 *  duplication, nothing deleted. See MembershipHoldWorker for the automatic
 *  ACTIVE -> HOLD transition. */
object MembershipState {
    const val ACTIVE = "ACTIVE"
    const val HOLD = "HOLD"
}

@Entity(
    tableName = "members",
    indices = [Index(value = ["phone"], unique = true)]
)
data class Member(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val photoPath: String?,
    val plan: String,
    val fee: Double,
    val joinedMillis: Long,
    val expiryMillis: Long,
    val historyJson: String,
    /** Used to resolve conflicts when merging records synced from another device:
     *  whichever copy of a record was edited most recently wins. */
    val updatedAtMillis: Long = System.currentTimeMillis(),
    /** PBKDF2 hash of the member's passkey. The plaintext passkey is shown to the
     *  owner exactly once at registration time (see PasskeyUtils) and never stored. */
    val passwordHash: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastAttendanceMillis: Long? = null,
    /** Unused/vestigial: previously part of an auto-delete safeguard that no
     *  longer exists (see [pendingDeletionMillis] and the Hold Members
     *  feature docs on [membershipState]/MembershipHoldWorker, which replaced
     *  automatic deletion entirely). Kept only so existing rows/backups with
     *  a stored value for it still read in without a schema change; nothing
     *  in the app sets or reads it anymore. */
    val archived: Boolean = false,
    /** Unique, single-use-window token behind the member's QR (add-on: time-limited
     *  membership QR). Regenerated on registration, on every renewal, and whenever the
     *  owner taps "Regenerate QR" — so a QR captured before a renewal can never be replayed
     *  to claim the member's updated plan/expiry. See QrUtils. */
    val qrToken: String = "",
    /** Epoch millis after which [qrToken] is no longer valid. */
    val qrTokenExpiryMillis: Long = 0,
    /** Optional government/institution ID reference (Aadhaar, PAN, college ID, etc.)
     *  — letters and digits only, validated in AddEditMemberScreen. Empty string
     *  means "not provided", matching the backup JSON's "idProof":"" convention. */
    val idProof: String = "",
    /** Local file path to an optional photo of the member's ID proof document.
     *  Empty string means "no photo", never null, so old records/backups without
     *  this field default in safely. */
    val idProofPhotoPath: String = "",
    /** ISO 19794-2 fingerprint template captured via a SecuGen USB scanner
     *  (see FingerprintScanner). Null means "not enrolled". This is a small
     *  (a few hundred byte) mathematical template, not a fingerprint image —
     *  the raw scan image is never stored. Used for 1:1 verification at
     *  check-in against the member the front-desk staff has already selected. */
    val fingerprintTemplate: ByteArray? = null,
    /** Unused/vestigial: previously tracked eligibility for an automatic
     *  deletion safeguard that has been removed entirely — a member is never
     *  deleted just for being long-expired anymore (see [membershipState]/
     *  MembershipHoldWorker, which moves them to HOLD instead, preserving
     *  every field). Kept only so existing rows/backups with a stored value
     *  still read in without a schema change; nothing in the app sets or
     *  reads it anymore. */
    val pendingDeletionMillis: Long? = null,
    /** ACTIVE or HOLD (see [MembershipState]) - Hold Members feature: a member
     *  whose membership has been expired for more than 2 months with no
     *  renewal is moved to HOLD (see MembershipHoldWorker) instead of being
     *  deleted or losing any data. Defaults to ACTIVE so every existing
     *  member/backup/sync record from before this feature reads in exactly
     *  as before. */
    val membershipState: String = MembershipState.ACTIVE
)
