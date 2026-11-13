package com.octos.aichat.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MemoryEntity::class], version = 4, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        /** v2 adds the retrieval-scoring columns; existing rows get safe defaults. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory ADD COLUMN keywords TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE memory ADD COLUMN importance INTEGER NOT NULL DEFAULT 5")
            }
        }

        /** v3 adds soft-delete bookkeeping; existing rows stay active (deletedAt = 0). */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory ADD COLUMN deletedBy TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v4 adds the semantic-embedding columns; existing rows get re-embedded lazily. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory ADD COLUMN embedding BLOB")
                db.execSQL("ALTER TABLE memory ADD COLUMN embeddingVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        fun get(context: Context): MemoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "memory.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
    }
}
