package com.clipgenius.ai.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [ProjectEntity::class, CaptionPresetEntity::class], version = 9, exportSchema = false)
@TypeConverters(ProjectTypeConverters::class)
abstract class ClipGeniusDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile
        private var INSTANCE: ClipGeniusDatabase? = null

        fun getDatabase(context: Context): ClipGeniusDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ClipGeniusDatabase::class.java,
                    "clipgenius_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
