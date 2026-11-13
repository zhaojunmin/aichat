package com.octos.aichat.data.memory

import kotlin.math.pow

/**
 * Three-factor memory retrieval, after Generative Agents (arXiv:2304.03442):
 *
 *     score = α_recency·recency + α_importance·importance + α_relevance·relevance
 *
 * Each factor is min-max normalised to [0,1] across the candidate set, then
 * summed (α all = 1 by default). This replaces the previous "most recently
 * updated N" injection with "most relevant-to-the-current-conversation N".
 *
 * [RelevanceScorer] is pluggable: the shipping default is a fully on-device
 * lexical overlap scorer (no embeddings, no network). A vector/embedding scorer
 * can be dropped in later without touching the scoring framework.
 */
object MemoryRetrieval {

    /** Weights for the three factors; all 1.0 mirrors the Generative Agents default. */
    data class Weights(
        val recency: Double = 1.0,
        val importance: Double = 1.0,
        val relevance: Double = 1.0,
    )

    /** Per-day exponential decay for recency (0.98 ≈ halves after ~34 days). */
    private const val RECENCY_DECAY_PER_DAY = 0.98
    private const val MILLIS_PER_DAY = 24.0 * 60 * 60 * 1000

    fun interface RelevanceScorer {
        /** Raw, un-normalised relevance of [item] to [queryTerms] (higher = closer). */
        fun score(item: MemoryEntity, queryTerms: Set<String>): Double
    }

    /**
     * Rank [items] against the current conversation [query] and return the top [k].
     * When [query] is blank (no conversation context), relevance contributes
     * nothing and ranking falls back to recency + importance — still an
     * improvement over pure recency.
     */
    fun retrieve(
        items: List<MemoryEntity>,
        query: String,
        now: Long,
        k: Int,
        weights: Weights = Weights(),
        relevance: RelevanceScorer = LexicalRelevance,
    ): List<MemoryEntity> {
        if (items.isEmpty() || k <= 0) return emptyList()
        if (items.size <= k && query.isBlank()) {
            return items.sortedWith(compareByDescending<MemoryEntity> { it.updatedAt }.thenByDescending { it.id })
        }

        val queryTerms = tokenize(query)
        val recencyRaw = items.map { RECENCY_DECAY_PER_DAY.pow((now - it.updatedAt).coerceAtLeast(0) / MILLIS_PER_DAY) }
        val importanceRaw = items.map { it.importance.toDouble() }
        val relevanceRaw =
            if (queryTerms.isEmpty()) items.map { 0.0 } else items.map { relevance.score(it, queryTerms) }

        val recency = normalize(recencyRaw)
        val importance = normalize(importanceRaw)
        val relevanceN = normalize(relevanceRaw)

        return items.indices
            .sortedByDescending {
                weights.recency * recency[it] +
                    weights.importance * importance[it] +
                    weights.relevance * relevanceN[it]
            }
            .take(k)
            .map { items[it] }
    }

    /** Min-max to [0,1]; a flat set (no spread) contributes nothing to the sum. */
    private fun normalize(values: List<Double>): List<Double> {
        val min = values.minOrNull() ?: return values
        val max = values.maxOrNull() ?: return values
        val range = max - min
        return if (range <= 0.0) values.map { 0.0 } else values.map { (it - min) / range }
    }

    /**
     * On-device lexical relevance: overlap between the query terms and the
     * memory's content + keywords, with keyword hits weighted higher. Dampened by
     * sqrt so longer memories don't dominate purely by length.
     */
    object LexicalRelevance : RelevanceScorer {
        override fun score(item: MemoryEntity, queryTerms: Set<String>): Double {
            if (queryTerms.isEmpty()) return 0.0
            val contentTerms = tokenize(item.content)
            val keywordTerms = tokenize(item.keywords)
            val contentHits = contentTerms.count { it in queryTerms }
            val keywordHits = keywordTerms.count { it in queryTerms }
            val raw = contentHits + 2.0 * keywordHits
            val size = (contentTerms.size + keywordTerms.size).coerceAtLeast(1)
            return raw / kotlin.math.sqrt(size.toDouble())
        }
    }

    /**
     * Language-agnostic tokeniser: CJK runs become overlapping character bigrams
     * (robust without a word segmenter), ASCII alphanumerics become lowercase
     * word tokens. Single CJK chars are also kept so one-character queries match.
     */
    internal fun tokenize(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val out = HashSet<String>()
        val cjk = StringBuilder()
        val ascii = StringBuilder()

        fun flushCjk() {
            val s = cjk.toString()
            when {
                s.length == 1 -> out.add(s)
                s.length >= 2 -> for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
            }
            cjk.setLength(0)
        }
        fun flushAscii() {
            if (ascii.isNotEmpty()) out.add(ascii.toString().lowercase())
            ascii.setLength(0)
        }

        for (ch in text) {
            when {
                isCjk(ch) -> { flushAscii(); cjk.append(ch) }
                ch.isLetterOrDigit() -> { flushCjk(); ascii.append(ch) }
                else -> { flushCjk(); flushAscii() }
            }
        }
        flushCjk(); flushAscii()
        return out
    }

    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0x3000..0x303F
}
