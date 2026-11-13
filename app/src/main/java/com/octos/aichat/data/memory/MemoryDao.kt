package com.octos.aichat.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {

    /** Active memories only — feeds retrieval/injection and the main list. */
    @Query("SELECT * FROM memory WHERE deletedAt = 0 ORDER BY category, id")
    suspend fun all(): List<MemoryEntity>

    /** Soft-deleted memories, most-recently-removed first — the recycle bin. */
    @Query("SELECT * FROM memory WHERE deletedAt > 0 ORDER BY deletedAt DESC")
    suspend fun allDeleted(): List<MemoryEntity>

    @Insert
    suspend fun insertAll(items: List<MemoryEntity>)

    /** Manual edit: overwrite text only, preserve retrieval columns. */
    @Query("UPDATE memory SET category = :category, content = :content, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: Long, category: String, content: String, updatedAt: Long)

    /** Learner conflict resolution: overwrite the row including retrieval columns. */
    @Query(
        "UPDATE memory SET category = :category, content = :content, " +
            "keywords = :keywords, importance = :importance, updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun updateFull(
        id: Long,
        category: String,
        content: String,
        keywords: String,
        importance: Int,
        updatedAt: Long,
    )

    /** Store a freshly computed embedding for one row. */
    @Query("UPDATE memory SET embedding = :embedding, embeddingVersion = :version WHERE id = :id")
    suspend fun setEmbedding(id: Long, embedding: ByteArray, version: Int)

    /** Active rows whose embedding is missing or stale — the backfill work list. */
    @Query("SELECT * FROM memory WHERE deletedAt = 0 AND (embedding IS NULL OR embeddingVersion < :version)")
    suspend fun needingEmbedding(version: Int): List<MemoryEntity>

    /** Invalidate a row's embedding (e.g. after its text changed) so backfill recomputes it. */
    @Query("UPDATE memory SET embedding = NULL, embeddingVersion = 0 WHERE id = :id")
    suspend fun clearEmbedding(id: Long)

    /** Soft-delete: move a row to the recycle bin ([by] = "learner" / "user"). */
    @Query("UPDATE memory SET deletedAt = :deletedAt, deletedBy = :by WHERE id = :id AND deletedAt = 0")
    suspend fun softDelete(id: Long, deletedAt: Long, by: String)

    /** Restore a soft-deleted row back to active. */
    @Query("UPDATE memory SET deletedAt = 0, deletedBy = '' WHERE id = :id")
    suspend fun restore(id: Long)

    /** Permanently drop one memory item (from the recycle bin). */
    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Only the manual "clear" button in settings ever wipes memory. */
    @Query("DELETE FROM memory")
    suspend fun clear()
}
