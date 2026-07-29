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
    val historyJson: String
)
