package com.octos.aichat.data.memory

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * R5 against a real Room database: a learning pass that fails part-way — including
 * between its memory writes and the offset update — rolls back completely, so the
 * retry neither loses the replies nor learns them a second time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LearningTransactionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: MemoryDatabase
    private lateinit var log: ReplyLog

    private val result = MemoryLearnResult(
        additions = listOf(MemoryDraft("偏好", "喜欢爬山", "爬山", 7)),
        updates = listOf(MemoryEdit(1, "事实", "搬到上海了", "上海", 8)),
        deletions = listOf(2),
    )

    @Before fun setUp() = runBlocking {
        File(context.filesDir, "my_replies.txt").delete()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java).build()
        db.memoryDao().insertAll(
            listOf(
                MemoryEntity(category = "事实", content = "住在北京", updatedAt = 1),
                MemoryEntity(category = "事实", content = "养了只猫", updatedAt = 1),
            )
        )
        log = ReplyLog(context, db.learningStateDao())
        log.append("我搬到上海了")
        Unit
    }

    @After fun tearDown() = db.close()

    private fun pass(dao: MemoryDao = db.memoryDao(), ledger: ReplyLedger = log) =
        MemoryLearningPass(ledger, dao, inTransaction = { block -> db.withTransaction { block() } })

    private suspend fun assertUntouched() {
        assertEquals(listOf("住在北京", "养了只猫"), db.memoryDao().all().map { it.content })
        assertEquals(emptyList<MemoryEntity>(), db.memoryDao().allDeleted())
        assertEquals("replies must stay pending", listOf("我搬到上海了"), log.pending().replies)
    }

    @Test fun writeFailingMidwayRollsBackEarlierWrites() = runBlocking {
        // The update and the soft-delete run before the insert that fails.
        val failingInsert = object : MemoryDao by db.memoryDao() {
            override suspend fun insertAll(items: List<MemoryEntity>) = error("disk full")
        }
        try {
            pass(dao = failingInsert).run { _, _ -> result }
            fail("expected the write failure to propagate")
        } catch (_: IllegalStateException) {
        }
        assertUntouched()
    }

    /** The window that used to make retries learn the same replies twice. */
    @Test fun offsetFailingAfterWritesRollsBackTheWrites() = runBlocking {
        val failingMark = object : ReplyLedger by log {
            override suspend fun markLearned(total: Int) = error("killed before the offset was saved")
        }
        try {
            pass(ledger = failingMark).run { _, _ -> result }
            fail("expected the offset failure to propagate")
        } catch (_: IllegalStateException) {
        }
        assertUntouched()
    }

    @Test fun successCommitsEverything_andSurvivesARestart() = runBlocking {
        assertEquals(1, pass().run { _, _ -> result })

        assertEquals(listOf("搬到上海了", "喜欢爬山"), db.memoryDao().all().map { it.content })
        assertEquals(listOf(2L), db.memoryDao().allDeleted().map { it.id })
        // A fresh ReplyLog (as after a process restart) sees the committed offset.
        val restarted = ReplyLog(context, db.learningStateDao())
        assertEquals(emptyList<String>(), restarted.pending().replies)
        assertEquals(1, db.learningStateDao().learnedReplies())
    }

    @Test fun retryAfterFailureLearnsOnce() = runBlocking {
        val failingInsert = object : MemoryDao by db.memoryDao() {
            override suspend fun insertAll(items: List<MemoryEntity>) = error("disk full")
        }
        runCatching { pass(dao = failingInsert).run { _, _ -> result } }

        var calls = 0
        pass().run { _, replies -> calls++; assertEquals(listOf("我搬到上海了"), replies); result }
        assertNull(pass().run { _, _ -> calls++; result })

        assertEquals(1, calls)
        assertEquals(1, db.memoryDao().all().count { it.content == "喜欢爬山" })
    }
}
