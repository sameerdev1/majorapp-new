package com.majorgym.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Member::class, AttendanceRecord::class, SyncChangeLogEntry::class], version = 11, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memberDao(): MemberDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun syncChangeLogDao(): SyncChangeLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE members ADD COLUMN updatedAtMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE members ADD COLUMN passwordHash TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE members ADD COLUMN createdAtMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE members ADD COLUMN lastAttendanceMillis INTEGER")
                db.execSQL("ALTER TABLE members ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_members_phone ON members(phone)")
            }
        }

        /** Add-on: unique, time-limited membership QR — see Member.qrToken. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE members ADD COLUMN qrToken TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE members ADD COLUMN qrTokenExpiryMillis INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Add-on: optional ID Proof text + ID Proof Photo (Features 2 & 3). Both
         *  default to '' so every existing row (and every restored old backup)
         *  reads as "not provided" rather than null/crashing. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE members ADD COLUMN idProof TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE members ADD COLUMN idProofPhotoPath TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Add-on: fingerprint enrollment (SecuGen USB scanner). Template is a small
         *  BLOB; NULL means "not enrolled" so existing members read in unaffected. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE members ADD COLUMN fingerprintTemplate BLOB")
            }
        }

        /** Add-on: auto-delete safeguard for long-expired accounts (Feature 4). NULL
         *  means "not currently pending deletion" — see Member.pendingDeletionMillis
         *  and MembershipCleanupWorker. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE members ADD COLUMN pendingDeletionMillis INTEGER")
            }
        }

        /** Attendance Logs (new feature): a new, additive table that logs every
         *  check-in event going forward - see [AttendanceRecord] for why this is
         *  needed alongside (not instead of) [Member.lastAttendanceMillis]. Doesn't
         *  touch the members table at all. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attendance_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        memberId TEXT NOT NULL,
                        timestampMillis INTEGER NOT NULL,
                        dayEpoch INTEGER NOT NULL,
                        session TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_records_memberId ON attendance_records(memberId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_records_dayEpoch ON attendance_records(dayEpoch)")
            }
        }

        /** Add-on: 4-month attendance retention + backup restore (Changes 1 & 2).
         *  Adds a unique (memberId, timestampMillis) index so repeated backup
         *  restores can't duplicate a row. A handful of pre-existing rows could
         *  theoretically already share that pair (only possible if the exact
         *  same member somehow checked in twice at the exact same millisecond),
         *  so any such duplicates are collapsed to one row first - otherwise
         *  creating the unique index below would fail outright. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM attendance_records WHERE id NOT IN (
                        SELECT MIN(id) FROM attendance_records GROUP BY memberId, timestampMillis
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_attendance_records_memberId_timestampMillis ON attendance_records(memberId, timestampMillis)"
                )
            }
        }

        /** Device Sync fix #2: attendance rows need a globally unique id (not
         *  just the per-device autoincrement [AttendanceRecord.id]) so the
         *  change log can reference them the same way on every device - see
         *  [AttendanceRecord.globalId]. SQLite can't fill an ADD COLUMN with
         *  a per-row random default directly, so this adds the column with a
         *  neutral '' default first, then fills every existing row with a
         *  real random id before the unique index is created (would fail on
         *  the very first migrate() otherwise, since every pre-existing row
         *  would still share the same '' value). */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE attendance_records ADD COLUMN globalId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE attendance_records SET globalId =
                        lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(2))) || '-' || lower(hex(randomblob(2))) || '-' ||
                        lower(hex(randomblob(6)))
                    WHERE globalId = ''
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_attendance_records_globalId ON attendance_records(globalId)")
            }
        }

        /** Device Sync fixes #1-#3: the change/event log Sync now replicates
         *  instead of comparing whole-record snapshots - see
         *  [SyncChangeLogEntry]'s class doc for why. A brand-new, empty table;
         *  existing Members/Attendance rows are untouched and simply have no
         *  history yet (their next edit on each device starts building it). */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_change_log (
                        changeId TEXT PRIMARY KEY NOT NULL,
                        entityType TEXT NOT NULL,
                        recordId TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        originDeviceId TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        timestampMillis INTEGER NOT NULL,
                        fieldsJson TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sync_change_log_originDeviceId_seq ON sync_change_log(originDeviceId, seq)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_log_recordId ON sync_change_log(recordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_change_log_entityType ON sync_change_log(entityType)")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "major_gym.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                ).build().also { INSTANCE = it }
            }
    }
}
