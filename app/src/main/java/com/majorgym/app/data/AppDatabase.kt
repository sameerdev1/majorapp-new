package com.majorgym.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Member::class], version = 6, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memberDao(): MemberDao

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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "major_gym.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
            }
    }
}
