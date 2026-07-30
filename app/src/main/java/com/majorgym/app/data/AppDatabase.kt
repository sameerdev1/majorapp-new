package com.majorgym.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Member::class], version = 3, exportSchema = false)
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

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "major_gym.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
