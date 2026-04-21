package com.piratedog.stencilforge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [StencilEntity::class], version = 3, exportSchema = false)
abstract class StencilDatabase : RoomDatabase() {
    abstract fun stencilDao(): StencilDao

    companion object {
        @Volatile private var INSTANCE: StencilDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stencils ADD COLUMN blurRadius REAL NOT NULL DEFAULT 0.3")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stencils ADD COLUMN sharpness REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE stencils ADD COLUMN edgeConnectivity REAL NOT NULL DEFAULT 0.4")
            }
        }

        fun getInstance(context: Context): StencilDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    StencilDatabase::class.java,
                    "stencil_forge.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
