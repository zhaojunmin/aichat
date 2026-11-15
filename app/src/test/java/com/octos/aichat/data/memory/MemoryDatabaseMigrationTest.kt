package com.octos.aichat.data.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrading the app must never lose memories. For every older schema version, build
 * the database exactly as that version created it, add rows, then open it with the
 * current [MemoryDatabase] and its real migration chain. Room validates the migrated
 * schema against the entities on open, so a migration that leaves a column with the
 * wrong type/default/nullability fails here instead of on a user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @After fun tearDown() {
        context.deleteDatabase(name)
    }

    /** `memory` columns as each version's fresh install created them. */
    private fun columns(version: Int): List<String> = buildList {
        add("`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL")
        add("`category` TEXT NOT NULL")
        add("`content` TEXT NOT NULL")
        add("`updatedAt` INTEGER NOT NULL")
        if (version >=
            2
        ) {
            add("`keywords` TEXT NOT NULL DEFAULT ''")
            add("`importance` INTEGER NOT NULL DEFAULT 5")
        }
        if (version >=
            3
        ) {
            add("`deletedAt` INTEGER NOT NULL DEFAULT 0")
            add("`deletedBy` TEXT NOT NULL DEFAULT ''")
        }
        if (version >=
            4
        ) {
            add("`embedding` BLOB")
            add("`embeddingVersion` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun createOldDatabase(version: Int) {
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE `memory` (${columns(version).joinToString()})")
            db.execSQL(
                "INSERT INTO memory (category, content, updatedAt) VALUES ('事实', '在北京做程序员', 100)",
            )
            db.execSQL(
                "INSERT INTO memory (category, content, updatedAt) VALUES ('偏好', '喜欢拿铁', 200)",
            )
            if (version >=
                3
            ) {
                db.execSQL("UPDATE memory SET deletedAt = 300, deletedBy = 'user' WHERE id = 2")
            }
            db.version = version
        }
    }

    private fun upgradeFrom(version: Int) = runBlocking {
        createOldDatabase(version)
        val db = Room.databaseBuilder(context, MemoryDatabase::class.java, name)
            .addMigrations(*MemoryDatabase.MIGRATIONS)
            .build()
        try {
            val dao = db.memoryDao()
            val all = dao.all() + dao.allDeleted()
            assertEquals(
                "v$version: no rows lost",
                setOf("在北京做程序员", "喜欢拿铁"),
                all.map {
                    it.content
                }.toSet(),
            )

            val first = all.single { it.id == 1L }
            assertEquals(100L, first.updatedAt)
            if (version < 2) {
                assertEquals("", first.keywords)
                assertEquals(5, first.importance)
            }
            assertEquals(0L, first.deletedAt)
            assertNull(first.embedding)
            assertEquals(0, first.embeddingVersion)
            // v3+ kept its recycle bin; older versions had none, so everything stays active.
            assertEquals(
                if (version >=
                    3
                ) {
                    listOf(2L)
                } else {
                    emptyList()
                },
                dao.allDeleted().map { it.id },
            )

            // The learner's progress starts unset; ReplyLog moves the old prefs value in.
            assertNull(db.learningStateDao().learnedReplies())
        } finally {
            db.close()
        }
    }

    @Test fun fromV1() = upgradeFrom(1)

    @Test fun fromV2() = upgradeFrom(2)

    @Test fun fromV3() = upgradeFrom(3)

    @Test fun fromV4() = upgradeFrom(4)
}
