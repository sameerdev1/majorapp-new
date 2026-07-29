package com.majorgym.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "members")
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
    val updatedAtMillis: Long = System.currentTimeMillis()
)
