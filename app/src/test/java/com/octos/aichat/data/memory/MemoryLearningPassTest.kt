package com.octos.aichat.data.memory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Reliability scenario R5: a learning round that fails anywhere must not lose the
 * un-learned replies (offset untouched → retried), and a round that succeeds must
 * not be learned again.
 */
class MemoryLearningPassTest {

    /** In-memory reply log: [lines] are all replies, [learned] the committed offset. */
    private class FakeLedger(vararg lines: String) : ReplyLedger {
        val lines = lines.toMutableList()
        var learned = 0
        override suspend fun pending() = PendingReplies(lines.drop(learned), lines.size)
        override fun markLearned(total: Int) { learned = total }
    }

    /** In-memory table; [failOn] makes the named write throw (simulates a crash mid-apply). */
    private class FakeDao(vararg rows: MemoryEntity) : MemoryDao {
        val rows = rows.toMutableList()
        var failOn: String? = null
        private var nextId = (rows.maxOfOrNull { it.id } ?: 0) + 1
        private fun maybeFail(op: String) { if (failOn == op) error("disk full during $op") }
        private fun edit(id: Long, f: (MemoryEntity) -> MemoryEntity) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = f(rows[i])
        }

        override suspend fun all() = rows.filter { it.deletedAt == 0L }
        override suspend fun allDeleted() = rows.filter { it.deletedAt > 0L }
        override suspend fun insertAll(items: List<MemoryEntity>) {
            maybeFail("insert"); items.forEach { rows += it.copy(id = nextId++) }
        }
        override suspend fun update(id: Long, category: String, content: String, updatedAt: Long) =
            edit(id) { it.copy(category = category, content = content, updatedAt = updatedAt) }
        override suspend fun updateFull(
            id: Long, category: String, content: String, keywords: String, importance: Int, updatedAt: Long,
        ) {
            maybeFail("update")
            edit(id) { it.copy(category = category, content = content, keywords = keywords, importance = importance, updatedAt = updatedAt) }
        }
        override suspend fun setEmbedding(id: Long, embedding: ByteArray, version: Int) =
            edit(id) { it.copy(embedding = embedding, embeddingVersion = version) }
        override suspend fun needingEmbedding(version: Int) = rows.filter { it.embeddingVersion < version }
        override suspend fun clearEmbedding(id: Long) = edit(id) { it.copy(embedding = null, embeddingVersion = 0) }
        override suspend fun softDelete(id: Long, deletedAt: Long, by: String) {
            maybeFail("delete"); edit(id) { if (it.deletedAt == 0L) it.copy(deletedAt = deletedAt, deletedBy = by) else it }
        }
        override suspend fun restore(id: Long) = edit(id) { it.copy(deletedAt = 0, deletedBy = "") }
        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun clear() = rows.clear()
    }

    // No embedding bytes: ByteArray breaks data-class equality. Version 1 = "has a vector".
    private fun mem(id: Long, content: String) =
        MemoryEntity(id = id, category = "事实", content = content, updatedAt = 1, embeddingVersion = 1)

    private val result = MemoryLearnResult(
        additions = listOf(MemoryDraft("偏好", "喜欢爬山", "爬山 户外", 7)),
        updates = listOf(MemoryEdit(1, "事实", "搬到上海了", "上海", 8)),
        deletions = listOf(2),
    )

    @Test fun success_appliesAllChangesThenAdvancesOffset() = runTest {
        val ledger = FakeLedger("周末去爬山", "我搬到上海了")
        val dao = FakeDao(mem(1, "住在北京"), mem(2, "养了只猫"))
        var seen: List<String>? = null

        val learned = MemoryLearningPass(ledger, dao) { 99 }.run { _, replies -> seen = replies; result }

        assertEquals(2, learned)
        assertEquals(2, ledger.learned)
        assertEquals(listOf("周末去爬山", "我搬到上海了"), seen)
        val updated = dao.rows.single { it.id == 1L }
        assertEquals("搬到上海了", updated.content)
        assertEquals(99L, updated.updatedAt)
        assertEquals("changed text must drop the stale vector", 0, updated.embeddingVersion)
        assertEquals(MemoryLearningPass.DELETED_BY_LEARNER, dao.rows.single { it.id == 2L }.deletedBy)
        assertEquals("喜欢爬山", dao.rows.single { it.id == 3L }.content)
    }

    @Test fun learnerGivesNothing_offsetUntouched() = runTest {
        val ledger = FakeLedger("a", "b")
        val dao = FakeDao(mem(1, "x"))

        assertNull(MemoryLearningPass(ledger, dao).run { _, _ -> null })

        assertEquals(0, ledger.learned)
        assertEquals(listOf(mem(1, "x")), dao.rows)
    }

    @Test fun learnerThrows_offsetUntouched() = runTest {
        val ledger = FakeLedger("a")
        try {
            MemoryLearningPass(ledger, FakeDao()).run { _, _ -> error("network down") }
            fail("expected the learner failure to propagate")
        } catch (_: IllegalStateException) {
        }
        assertEquals(0, ledger.learned)
    }

    @Test fun writeFailsMidApply_offsetUntouched_andRetrySucceeds() = runTest {
        val ledger = FakeLedger("我搬到上海了")
        val dao = FakeDao(mem(1, "住在北京"), mem(2, "养了只猫")).apply { failOn = "insert" }
        val pass = MemoryLearningPass(ledger, dao)

        try {
            pass.run { _, _ -> result }
            fail("expected the write failure to propagate")
        } catch (_: IllegalStateException) {
        }
        assertEquals("replies must stay pending after a failed write", 0, ledger.learned)

        dao.failOn = null
        var retried: List<String>? = null
        assertEquals(1, pass.run { _, replies -> retried = replies; result })
        assertEquals(listOf("我搬到上海了"), retried)
        assertEquals(1, ledger.learned)
    }

    @Test fun afterSuccess_sameRepliesAreNotLearnedAgain() = runTest {
        val ledger = FakeLedger("a", "b")
        val pass = MemoryLearningPass(ledger, FakeDao())
        var calls = 0
        val learn: suspend (List<MemoryEntity>, List<String>) -> MemoryLearnResult? =
            { _, _ -> calls++; MemoryLearnResult(emptyList(), emptyList()) }

        pass.run(learn)
        assertNull(pass.run(learn))
        assertEquals(1, calls)

        ledger.lines += "c"
        var replies: List<String>? = null
        pass.run { _, r -> replies = r; calls++; MemoryLearnResult(emptyList(), emptyList()) }
        assertEquals("only the new reply is learned", listOf("c"), replies)
        assertEquals(3, ledger.learned)
    }

    @Test fun nothingPending_learnerNotCalled() = runTest {
        var called = false
        assertNull(MemoryLearningPass(FakeLedger(), FakeDao()).run { _, _ -> called = true; null })
        assertTrue(!called)
    }

    @Test fun inventedIdsAreIgnored() = runTest {
        val dao = FakeDao(mem(1, "住在北京"))
        val bogus = MemoryLearnResult(
            additions = emptyList(),
            updates = listOf(MemoryEdit(42, "事实", "胡编的")),
            deletions = listOf(43, 1_000),
        )

        assertEquals(1, MemoryLearningPass(FakeLedger("x"), dao).run { _, _ -> bogus })

        assertEquals(listOf(mem(1, "住在北京")), dao.rows)
    }

    @Test fun alreadyDeletedRowsAreNotShownOrTouched() = runTest {
        val gone = mem(5, "旧爱好").copy(deletedAt = 10, deletedBy = "user")
        val dao = FakeDao(mem(1, "住在北京"), gone)
        var shown: List<MemoryEntity>? = null

        MemoryLearningPass(FakeLedger("x"), dao).run { existing, _ ->
            shown = existing
            MemoryLearnResult(emptyList(), listOf(MemoryEdit(5, "事实", "复活")), listOf(5))
        }

        assertEquals(listOf(1L), shown!!.map { it.id })
        assertEquals(gone, dao.rows.single { it.id == 5L })
    }
}
