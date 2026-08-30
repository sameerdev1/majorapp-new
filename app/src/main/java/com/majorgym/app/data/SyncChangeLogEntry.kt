package com.majorgym.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** [SyncChangeLogEntry.entityType] values. */
const val ENTITY_MEMBER = "MEMBER"
const val ENTITY_ATTENDANCE = "ATTENDANCE"

/** [SyncChangeLogEntry.operation] values. */
const val OP_ADD = "ADD"
const val OP_UPDATE = "UPDATE"
const val OP_DELETE = "DELETE"

/**
 * One real, permanent record of a change to a Member or Attendance record -
 * an "event", not just a snapshot. This is what makes Device Sync fault
 * tolerant (fix #3): instead of comparing whole records and guessing what
 * happened from a single "last modified time", every Add/Update/Delete is
 * captured here with everything needed to replay it deterministically on any
 * other device:
 *
 * - [changeId] - unique id for this exact change, so receiving the same
 *   change twice (repeated sync, gossip relaying it a second time) is a
 *   trivially detectable no-op (`OnConflictStrategy.IGNORE` on this primary
 *   key) - the idempotency the spec requires.
 * - [recordId] - which Member (its [Member.id]) or Attendance visit (its
 *   [AttendanceRecord.globalId]) this change applies to.
 * - [operation] - ADD / UPDATE / DELETE.
 * - [originDeviceId] - which device actually made this change.
 * - [seq] - that device's own monotonically increasing counter (1, 2, 3...),
 *   scoped to [originDeviceId]. Together, (originDeviceId, seq) is how two
 *   devices figure out - without a central server - exactly which changes
 *   each one is still missing: "send me everything from device X after seq
 *   N" (a version vector). This is what makes an offline device catch up
 *   correctly no matter how long it was gone, and what lets changes
 *   transitively "gossip" through a third device that already synced with
 *   the true originator.
 * - [timestampMillis] - wall-clock time, used only to order changes to the
 *   SAME record for field-level merge (fix #3) - never used as the sole
 *   signal for "did this happen" the way [Member.updatedAtMillis] used to be.
 * - [fieldsJson] - for ADD, every field of the new record; for UPDATE, only
 *   the fields that actually changed (so two devices editing different
 *   fields of the same Member never clobber each other - see
 *   Repository.recomputeAndApplyMember); null for DELETE, which needs no
 *   payload beyond "this id is gone".
 *
 * Deliberately never modified or deleted once written (an immutable log, not
 * a mutable "current state" table) - the merged/current state of a record is
 * always *derived* from replaying its full history
 * ([Repository.recomputeAndApplyMember] / `recomputeAndApplyAttendance`), so
 * that replay gives the same answer on every device regardless of the order
 * changes arrived in, which is exactly what "eventual consistency... not
 * dependent on one device being the master" requires.
 */
@Entity(
    tableName = "sync_change_log",
    indices = [
        Index(value = ["originDeviceId", "seq"], unique = true),
        Index("recordId"),
        Index("entityType")
    ]
)
data class SyncChangeLogEntry(
    @PrimaryKey val changeId: String,
    val entityType: String,
    val recordId: String,
    val operation: String,
    val originDeviceId: String,
    val seq: Long,
    val timestampMillis: Long,
    val fieldsJson: String? = null
)

/** One row of [SyncChangeLogDao.versionVectorRaw] - Room needs a concrete
 *  return type for a multi-column aggregate query. */
data class DeviceSeq(val originDeviceId: String, val maxSeq: Long)
