package com.togotiming.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ActivityTypeEntity::class, TimeRecordEntity::class, NfcBindingEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityTypeDao(): ActivityTypeDao
    abstract fun timeRecordDao(): TimeRecordDao
    abstract fun nfcBindingDao(): NfcBindingDao

    companion object {

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE activity_types ADD COLUMN targetMinutes INTEGER NOT NULL DEFAULT 60"
                )
                db.execSQL("ALTER TABLE time_records ADD COLUMN targetMinutes INTEGER")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "timing.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
