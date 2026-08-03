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
    /** Members expired 180+ days are archived (spec section 8) rather than deleted.
     *  Archived members are hidden from the default list but remain searchable/restorable. */
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
    val idProofPhotoPath: String = ""
)
