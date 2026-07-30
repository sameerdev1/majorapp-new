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
    val archived: Boolean = false
)
