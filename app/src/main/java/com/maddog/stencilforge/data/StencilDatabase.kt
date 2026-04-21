package com.maddog.stencilforge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [StencilEntity::class], version = 1, exportSchema = false)
abstract class StencilDatabase : RoomDatabase() {
    abstract fun stencilDao(): StencilDao

    companion object {
        @Volatile private var INSTANCE: StencilDatabase? = null

        fun getInstance(context: Context): StencilDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    StencilDatabase::class.java,
                    "stencil_forge.db"
                ).build().also { INSTANCE = it }
            }
    }
}
