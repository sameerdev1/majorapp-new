package com.majorgym.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {
    @Query("SELECT * FROM members ORDER BY name ASC")
    fun getAll(): Flow<List<Member>>

    @Query("SELECT * FROM members")
    suspend fun getAllOnce(): List<Member>

    /** Used to enforce the "phone number is already registered" rule (spec section 1)
     *  before attempting an insert, so the owner gets a friendly message instead of a
     *  raw SQLite constraint-violation crash. [excludingId] lets an edit screen check
     *  against every *other* member without flagging the record's own phone number. */
    @Query("SELECT COUNT(*) FROM members WHERE phone = :phone AND id != :excludingId")
    suspend fun countByPhone(phone: String, excludingId: String = ""): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: Member)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<Member>)

    @Delete
    suspend fun delete(member: Member)

    @Query("DELETE FROM members")
    suspend fun clearAll()
}
