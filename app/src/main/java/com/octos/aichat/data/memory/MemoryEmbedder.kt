package com.octos.aichat.data.memory

import android.content.Context
import com.octos.aichat.ai.embedding.Embedder

/**
 * Owns the on-device semantic-embedding lifecycle for memory: loading the model
 * and back-filling vectors for rows that are missing/stale. Everything is
 * best-effort — no-ops when the model/native engine isn't ready, so callers never
 * need to branch on readiness. Single responsibility: memory → vectors.
 *
 * Depends only on the [Embedder] abstraction; the concrete on-device engine is
 * injected (default) so this is unit-testable with a fake.
 */
class MemoryEmbedder(
    private val context: Context,
    private val dao: MemoryDao,
    private val embedder: Embedder = Embedder.onDevice,
) {

    /** Load the model if [enabled]; returns whether the engine is now usable. */
    fun prepare(enabled: Boolean): Boolean =
        enabled && embedder.prepare(context)

    /**
     * Compute + store vectors for active rows with missing/stale embeddings.
     * @return true if at least one vector was written (so the caller can refresh).
     */
    suspend fun backfill(): Boolean {
        if (!embedder.isReady) return false
        val todo = dao.needingEmbedding(embedder.version)
        if (todo.isEmpty()) return false
        var wrote = false
        todo.forEach { m ->
            val text = listOf(m.content, m.keywords).filter { it.isNotBlank() }.joinToString(" ")
            val v = embedder.embed(text) ?: return@forEach
            dao.setEmbedding(m.id, embedder.encode(v), embedder.version)
            wrote = true
        }
        return wrote
    }
}
