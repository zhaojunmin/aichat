package com.octos.aichat.data.memory

import android.content.Context
import com.octos.aichat.ai.embedding.Embedder
import com.octos.aichat.ai.embedding.VectorCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrievalTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun mem(
        id: Long,
        content: String,
        ageDays: Long = 0,
        importance: Int = 5,
        keywords: String = "",
        embedding: ByteArray? = null,
    ) = MemoryEntity(
        id = id, category = "事实", content = content,
        updatedAt = now - ageDays * day, keywords = keywords,
        importance = importance, embedding = embedding,
    )

    // ---- tokenize -----------------------------------------------------------

    @Test fun tokenize_cjkBigramsAndLowercasedAscii() {
        assertEquals(setOf("喜欢", "欢喝", "喝咖", "咖啡", "latte"), MemoryRetrieval.tokenize("喜欢喝咖啡 Latte"))
    }

    @Test fun tokenize_singleCjkCharKept() {
        assertEquals(setOf("猫", "cat"), MemoryRetrieval.tokenize("猫,cat"))
    }

    @Test fun tokenize_blankIsEmpty() {
        assertTrue(MemoryRetrieval.tokenize("  ").isEmpty())
    }

    // ---- retrieve -----------------------------------------------------------

    @Test fun retrieve_emptyOrZeroK() {
        assertTrue(MemoryRetrieval.retrieve(emptyList(), "q", now, 3).isEmpty())
        assertTrue(MemoryRetrieval.retrieve(listOf(mem(1, "x")), "q", now, 0).isEmpty())
    }

    @Test fun retrieve_blankQueryFewItems_sortsByRecency() {
        val items = listOf(mem(1, "old", ageDays = 10), mem(2, "new", ageDays = 1), mem(3, "mid", ageDays = 5))
        assertEquals(listOf(2L, 3L, 1L), MemoryRetrieval.retrieve(items, "", now, 5).map { it.id })
    }

    @Test fun retrieve_relevanceBeatsRecencyWhenOtherFactorsEqual() {
        val items = listOf(
            mem(1, "周末喜欢去爬山", ageDays = 30, keywords = "爬山 户外"),
            mem(2, "在北京做程序员", ageDays = 0),
            mem(3, "养了一只猫", ageDays = 0),
        )
        val top = MemoryRetrieval.retrieve(items, "这周末一起去爬山吗", now, 1)
        assertEquals(1L, top.single().id)
    }

    @Test fun retrieve_importanceBreaksTieWithoutQuery() {
        val items = listOf(mem(1, "a", importance = 2), mem(2, "b", importance = 9), mem(3, "c", importance = 5))
        assertEquals(2L, MemoryRetrieval.retrieve(items, "", now, 1).single().id)
    }

    @Test fun retrieve_respectsK() {
        val items = (1L..10L).map { mem(it, "条目$it", ageDays = it) }
        assertEquals(4, MemoryRetrieval.retrieve(items, "条目", now, 4).size)
    }

    @Test fun lexical_keywordHitsWeighMoreThanContentHits() {
        val inKeywords = mem(1, "无关内容", keywords = "咖啡")
        val inContent = mem(2, "咖啡")
        val s = MemoryRetrieval.LexicalRelevance.scores(listOf(inKeywords, inContent), "咖啡")
        assertTrue(s[0] > 0 && s[1] > 0)
    }

    // ---- embedding relevance -------------------------------------------------

    private class FakeEmbedder(private val query: FloatArray?) : Embedder {
        override val isReady = query != null
        override val version = 1
        override fun prepare(context: Context) = isReady
        override fun embed(text: String) = query
        override fun embedQuery(text: String) = query
        override fun encode(vector: FloatArray) = VectorCodec.encode(vector)
        override fun decode(bytes: ByteArray) = VectorCodec.decode(bytes)
    }

    @Test fun embedding_cosineRanksClosestVectorFirst() {
        val items = listOf(
            mem(1, "a", embedding = VectorCodec.encode(floatArrayOf(0f, 1f))),
            mem(2, "b", embedding = VectorCodec.encode(floatArrayOf(1f, 0.1f))),
            mem(3, "c", embedding = null),
        )
        val scorer = MemoryRetrieval.EmbeddingRelevance(FakeEmbedder(floatArrayOf(1f, 0f)))
        val s = scorer.scores(items, "q")
        assertTrue(s[1] > s[0])
        assertEquals(0.0, s[2], 0.0)
    }

    @Test fun embedding_notReadyOrDimMismatchScoresZero() {
        val items = listOf(mem(1, "a", embedding = VectorCodec.encode(floatArrayOf(1f, 0f, 0f))))
        val notReady = MemoryRetrieval.EmbeddingRelevance(FakeEmbedder(null)).scores(items, "q")
        val mismatch = MemoryRetrieval.EmbeddingRelevance(FakeEmbedder(floatArrayOf(1f, 0f))).scores(items, "q")
        assertEquals(0.0, notReady[0], 0.0)
        assertEquals(0.0, mismatch[0], 0.0)
    }
}
