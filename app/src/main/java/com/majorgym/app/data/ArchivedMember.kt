package com.majorgym.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lightweight historical record created by [MembershipCleanupWorker] (via
 * [Repository.archiveMember]) once a member has been expired 30+ days with
 * no renewal. Deliberately NOT a copy of the full [Member] entity - no
 * photo, no ID proof photo, no fingerprint template, no QR token, no
 * password hash, and no attendance history (that's permanently deleted at
 * archive time, see [Repository.archiveMember]) - just enough to identify a
 * returning customer and show what they last had.
 *
 * [originalMemberId] is the primary key so archiving the same member twice
 * (e.g. the cleanup worker running again, or applying the same backup
 * twice) is a no-op (OnConflictStrategy.IGNORE on insert - see
 * [ArchivedMemberDao]) rather than a duplicate row.
 */
@Entity(
    tableName = "archived_members",
    indices = [Index(value = ["phone"]), Index(value = ["name"])]
)
data class ArchivedMember(
    @PrimaryKey val originalMemberId: String,
    val name: String,
    val phone: String,
    /** Original join date, unchanged from the operational [Member.joinedMillis]. */
    val joinedMillis: Long,
    val lastPlan: String,
    val lastFee: Double,
    /** Start of the member's last membership cycle - derived at archive time
     *  from [lastExpiryMillis] minus the plan's duration (see
     *  [Repository.archiveMember]); falls back to [joinedMillis] if the plan
     *  name isn't recognized. */
    val lastStartMillis: Long,
    val lastExpiryMillis: Long,
    /** Optional government/institution ID reference, carried over as-is - no photo. */
    val idProof: String = "",
    val archivedAtMillis: Long
)
