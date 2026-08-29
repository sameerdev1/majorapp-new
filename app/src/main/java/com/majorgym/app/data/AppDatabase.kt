package com.majorgym.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Member::class, AttendanceRecord::class], version = 9, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memberDao(): MemberDao
    abstract fun attendanceDao(): AttendanceDao

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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "major_gym.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build().also { INSTANCE = it }
            }
    }
}
