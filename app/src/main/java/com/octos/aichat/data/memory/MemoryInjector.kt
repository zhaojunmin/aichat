package com.octos.aichat.data.memory

import com.octos.aichat.ai.embedding.Embedder
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.Conversation

/**
 * Turns the stored memories into the compact block injected into the suggestion-generation
 * prompt: ranks by the three-factor score (recency + importance + relevance-to-this-chat),
 * takes the top-N, and formats them grouped by category. Relevance uses on-device
 * semantic embeddings when enabled + ready, else local lexical matching.
 * Single responsibility: memory → prompt text.
 */
object MemoryInjector {

    fun block(
        items: List<MemoryEntity>,
        settings: AppSettings,
        conv: Conversation?,
        embedder: Embedder = Embedder.onDevice,
    ): String {
        if (!settings.memoryEnabled || items.isEmpty()) return ""
        val limit = settings.memoryInjectLimit.coerceAtLeast(1)
        // Query = the recent transcript; the other party's latest lines are what we
        // are about to reply to, so relevance should favour memories about them.
        val query = conv?.toTranscript(maxLines = 12).orEmpty()
        val relevance = if (settings.memoryEmbeddingEnabled && embedder.isReady) {
            MemoryRetrieval.EmbeddingRelevance(embedder)
        } else {
            MemoryRetrieval.LexicalRelevance
        }
        val selected = MemoryRetrieval.retrieve(
            items = items,
            query = query,
            now = System.currentTimeMillis(),
            k = limit,
            relevance = relevance,
        )
        return selected
            .groupBy { it.category }
            .entries.joinToString("\n") { (cat, rows) ->
                "【$cat】" + rows.joinToString("；") { it.content }
            }
    }
}
