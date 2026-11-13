package com.octos.aichat.data.memory

import android.content.Context

/**
 * Owns the on-device semantic-embedding lifecycle for memory: loading the model
 * and back-filling vectors for rows that are missing/stale. Everything is
 * best-effort — no-ops when the model/native engine isn't ready, so callers never
 * need to branch on readiness. Single responsibility: memory → vectors.
 */
class MemoryEmbedder(
    private val context: Context,
    private val dao: MemoryDao,
) {

    /** Load the model if [enabled]; returns whether the engine is now usable. */
    fun prepare(enabled: Boolean): Boolean =
        enabled && EmbeddingModelManager.ensureReady(context)

    /**
     * Compute + store vectors for active rows with missing/stale embeddings.
     * @return true if at least one vector was written (so the caller can refresh).
     */
    suspend fun backfill(): Boolean {
        if (!EmbeddingEngine.isReady) return false
        val todo = dao.needingEmbedding(EmbeddingEngine.VERSION)
        if (todo.isEmpty()) return false
        var wrote = false
        todo.forEach { m ->
            val text = listOf(m.content, m.keywords).filter { it.isNotBlank() }.joinToString(" ")
            val v = EmbeddingEngine.embed(text) ?: return@forEach
            dao.setEmbedding(m.id, EmbeddingCodec.encode(v), EmbeddingEngine.VERSION)
            wrote = true
        }
        return wrote
    }
}
