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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(member: Member)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<Member>)

    @Delete
    suspend fun delete(member: Member)

    @Query("DELETE FROM members")
    suspend fun clearAll()
}
