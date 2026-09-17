package net.marscore.webhub.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ChildApp::class], version = 1, exportSchema = false)
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
                ).build().also { INSTANCE = it }
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