package com.maddog.stencilforge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [StencilEntity::class], version = 2, exportSchema = false)
abstract class StencilDatabase : RoomDatabase() {
    abstract fun stencilDao(): StencilDao

    companion object {
        @Volatile private var INSTANCE: StencilDatabase? = null

        // Adds blurRadius column with default 0.3
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stencils ADD COLUMN blurRadius REAL NOT NULL DEFAULT 0.3")
            }
        }

        fun getInstance(context: Context): StencilDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    StencilDatabase::class.java,
                    "stencil_forge.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
