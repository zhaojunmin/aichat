package com.octos.aichat.benchmark

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.octos.aichat.ai.embedding.Embedder
import com.octos.aichat.ai.embedding.VectorCodec
import com.octos.aichat.ai.embedding.WordPieceTokenizer
import com.octos.aichat.bubble.SuggestionGenerator
import com.octos.aichat.data.memory.MemoryEntity
import com.octos.aichat.data.memory.MemoryLearner
import com.octos.aichat.data.memory.MemoryRetrieval
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device microbenchmarks for the pure-compute pieces on the suggestion path
 * (budget P5 in docs/quality-attributes.md: memory recall ≤ 50 ms).
 * Data sizes mirror real use: up to 500 memories, 512-dim bge vectors, and a
 * query built the way MemoryPromptBuilder does (last 12 transcript lines).
 */
@RunWith(AndroidJUnit4::class)
class HotPathBenchmarks {

    @get:Rule
    val benchmark = BenchmarkRule()

    companion object {
        private const val DIM = 512
        private const val MEMORIES = 500
        private val rnd = Random(42)

        private val WORDS = listOf(
            "北京", "程序员", "咖啡", "拿铁", "爬山", "周末", "猫", "火锅", "电影", "健身",
            "上海", "出差", "旅行", "摄影", "篮球", "加班", "早起", "音乐", "读书", "做饭",
        )

        private fun sentence(n: Int) = (1..n).joinToString("") { WORDS[rnd.nextInt(WORDS.size)] }

        private fun vector() = FloatArray(DIM) { rnd.nextFloat() - 0.5f }

        val now = System.currentTimeMillis()

        /** 500 memories with keywords, varied age/importance, and stored vectors. */
        val memories: List<MemoryEntity> = List(MEMORIES) { i ->
            MemoryEntity(
                id = i.toLong(),
                category = "事实",
                content = sentence(6),
                updatedAt = now - rnd.nextLong(0, 180L * 24 * 3600 * 1000),
                keywords = (1..3).joinToString(" ") { WORDS[rnd.nextInt(WORDS.size)] },
                importance = rnd.nextInt(1, 11),
                embedding = VectorCodec.encode(vector()),
            )
        }

        /** Query = last 12 transcript lines — MemoryPromptBuilder uses toTranscript(maxLines = 12). */
        val query: String = (1..12).joinToString("\n") {
            (if (it % 2 == 0) "我: " else "对方: ") + sentence(4)
        }

        /** Fixed query vector: isolates the scoring cost from the model forward pass. */
        private val queryVector = vector()

        val fakeEmbedder = object : Embedder {
            override val isReady = true
            override val version = 1
            override fun prepare(context: Context) = true
            override fun embed(text: String) = queryVector
            override fun embedQuery(text: String) = queryVector
            override fun encode(vector: FloatArray) = VectorCodec.encode(vector)
            override fun decode(bytes: ByteArray) = VectorCodec.decode(bytes)
        }

        private val context: Context
            get() = InstrumentationRegistry.getInstrumentation().targetContext

        lateinit var tokenizer: WordPieceTokenizer

        @BeforeClass @JvmStatic
        fun loadVocab() {
            val f = File(context.cacheDir, "bench_vocab.txt")
            context.assets.open("embed/vocab.txt").use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            tokenizer = WordPieceTokenizer.fromFile(f.path)
        }
    }

    // ---- memory recall (P5) -------------------------------------------------

    @Test fun recall_lexical_500() = benchmark.measureRepeated {
        MemoryRetrieval.retrieve(memories, query, now, k = 30)
    }

    /** Semantic scoring only (decode 500 stored vectors + cosine); query vector precomputed. */
    @Test fun recall_semanticScoring_500() = benchmark.measureRepeated {
        MemoryRetrieval.retrieve(
            memories,
            query,
            now,
            k = 30,
            relevance = MemoryRetrieval.EmbeddingRelevance(fakeEmbedder),
        )
    }

    /** The real bge-small-zh forward pass for one query (MNN, on-device). */
    @Test fun embedQuery_bgeModel() {
        val embedder = Embedder.onDevice
        assumeTrue("on-device embedding needs arm64 native libs", embedder.prepare(context))
        assertNotNull(embedder.embedQuery(query))
        benchmark.measureRepeated { embedder.embedQuery(query) }
    }

    // ---- building blocks ----------------------------------------------------

    @Test fun tokenize_query() = benchmark.measureRepeated {
        tokenizer.encode(query)
    }

    @Test fun vectorDecode_512() {
        val bytes = memories[0].embedding!!
        benchmark.measureRepeated { VectorCodec.decode(bytes) }
    }

    // ---- model output parsing ----------------------------------------------

    private val generator = SuggestionGenerator()
    private val learner = MemoryLearner()
    private val suggestionsRaw = "```json\n[\"周末一起去爬山吧\",\"好呀,几点出发?\",\"最近有点累,下次吧\"]\n```"
    private val learnRaw = """{"additions":[{"category":"事实","content":"在北京做程序员",
        "keywords":"北京 程序员 工作","importance":8}],
        "updates":[{"id":12,"category":"偏好","content":"现在改喝拿铁",
        "keywords":"拿铁 咖啡","importance":5}],"deletions":[7]}"""

    @Test fun parseSuggestions() = benchmark.measureRepeated {
        generator.parseSuggestions(suggestionsRaw, 3)
    }

    @Test fun parseLearnResult() = benchmark.measureRepeated {
        learner.parseLearnResult(learnRaw)
    }
}
