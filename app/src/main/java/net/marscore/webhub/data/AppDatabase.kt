package net.marscore.webhub.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration v1 → v2: adds the `lastOpenedAt` column to `child_app`. Existing rows keep
 * their data and get `lastOpenedAt = 0` (treated as "never opened"). See OpenSpec change
 * `add-home-screen-widgets` (design D8).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE child_app ADD COLUMN lastOpenedAt INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Room migration v2 → v3: adds the `displayMode` column to `child_app`. Existing rows keep
 * their data and get `displayMode = 'system'` (status bar visible + theme-color tint;
 * see [ChildApp.displayMode]). See OpenSpec change `improve-webapp-shell-ux` task 5.2 /
 * design D1.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE child_app ADD COLUMN displayMode TEXT NOT NULL DEFAULT 'system'")
    }
}

@Database(entities = [ChildApp::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun childAppDao(): ChildAppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "webhub.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }

        /** Test-only: clear the cached singleton so a fresh Context gets a fresh database. */
        fun resetForTest() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}