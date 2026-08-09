package com.majorgym.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    /** Members expired 180+ days used to be described here as "archived, not
     *  deleted" but that was never actually implemented anywhere — this field
     *  is repurposed as the auto-delete safeguard instead (see
     *  MembershipCleanupWorker): true only in the brief window between a
     *  member first becoming eligible for deletion and the cleanup job
     *  confirming it a second time ~a day later before actually deleting them.
     *  Lets the UI (if ever needed) flag "pending removal" without hiding the
     *  member outright. */
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
    /**
     * Epoch millis of when this member first became eligible for automatic
     * deletion (expired 4+ months, never renewed since — see
     * MembershipCleanupWorker). Null means "not currently pending." Renewing
     * clears this automatically, since renewal moves [expiryMillis] into the
     * future and the member stops being eligible. This — combined with
     * [archived] — is the safeguard against a one-off clock glitch or sync
     * hiccup causing an instant, irreversible deletion: a member has to stay
     * eligible across two separate daily checks before they're actually removed.
     */
    val pendingDeletionMillis: Long? = null
)
