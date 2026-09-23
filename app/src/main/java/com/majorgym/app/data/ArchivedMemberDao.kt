package com.majorgym.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchivedMemberDao {
    @Query("SELECT * FROM archived_members ORDER BY archivedAtMillis DESC")
    fun getAll(): Flow<List<ArchivedMember>>

    @Query("SELECT * FROM archived_members")
    suspend fun getAllOnce(): List<ArchivedMember>

    @Query("SELECT * FROM archived_members WHERE originalMemberId = :id LIMIT 1")
    suspend fun getByIdOnce(id: String): ArchivedMember?

    /** Idempotent archive create - see [ArchivedMember]'s doc: a duplicate
     *  originalMemberId is silently ignored rather than replacing/erroring,
     *  so re-running the cleanup worker (or applying the same change twice)
     *  can never create a second archive row for the same member. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicate(record: ArchivedMember)

    /** Backup restore: same idempotency guard as above - restoring the same
     *  backup twice never duplicates an archive row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringDuplicates(records: List<ArchivedMember>)

    /** Removes the archive row once the owner restores/renews the member
     *  back into the operational Members table (see
     *  [Repository.restoreArchivedMember]). */
    @Query("DELETE FROM archived_members WHERE originalMemberId = :id")
    suspend fun deleteById(id: String)
}
