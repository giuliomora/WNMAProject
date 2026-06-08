package com.example.trekmesh.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 1, exportSchema = false)
abstract class TrekMeshDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: TrekMeshDatabase? = null

        fun getInstance(context: Context): TrekMeshDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrekMeshDatabase::class.java,
                    "trekmesh.db"
                ).build().also { INSTANCE = it }
            }
    }
}
