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

    /** Single-record lookup used by Sync's change diffing (fix #3: needs the
     *  current stored copy to know which fields actually changed) and by
     *  merge-replay (needs to know whether an incoming record is new). */
    @Query("SELECT * FROM members WHERE id = :id LIMIT 1")
    suspend fun getByIdOnce(id: String): Member?

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

    /** Deletion applied from a synced device's change log (fix #1/#3): the
     *  peer only sends us a tombstone (id + operation=DELETE), not a full
     *  [Member] object to pass to [delete], so this deletes by id directly. */
    @Query("DELETE FROM members WHERE id = :id")
    suspend fun deleteById(id: String)
}
