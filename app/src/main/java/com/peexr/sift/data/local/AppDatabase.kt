package com.peexr.sift.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScreenshotEntity::class,
        CollectionEntity::class,
        ScreenshotCollectionCrossRef::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun screenshotDao(): ScreenshotDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sift_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            createFtsObjects(db)
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            // insertScreenshots uses OnConflictStrategy.REPLACE. SQLite fires DELETE triggers
                            // for REPLACE-deleted rows ONLY when PRAGMA recursive_triggers = ON; otherwise
                            // the old FTS entry goes stale and the index corrupts.
                            db.query("PRAGMA recursive_triggers = ON").close()
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
