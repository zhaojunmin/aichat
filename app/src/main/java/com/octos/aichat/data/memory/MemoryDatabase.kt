package com.octos.aichat.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Schemas are exported to app/schemas (one JSON per version) so future migrations
// can be checked against the exact previous schema.
@Database(entities = [MemoryEntity::class, LearningState::class], version = 5, exportSchema = true)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    abstract fun learningStateDao(): LearningStateDao

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

        /**
         * v5 adds the learner's progress row, so it commits together with the memory
         * changes it records. Left empty here: the old SharedPreferences value is moved
         * in by [ReplyLog] on first read (a migration has no access to prefs).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `learning_state` " +
                        "(`id` INTEGER NOT NULL, `learnedReplies` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        /** Every upgrade step, in order. Internal so the migration test runs the real chain. */
        internal val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        fun get(context: Context): MemoryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "memory.db",
                ).addMigrations(*MIGRATIONS).build().also { INSTANCE = it }
            }
    }
}
