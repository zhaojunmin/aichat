package com.octos.aichat.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memory ORDER BY category, id")
    suspend fun all(): List<MemoryEntity>

    @Insert
    suspend fun insertAll(items: List<MemoryEntity>)

    /** Overwrite one existing row (conflict resolution, newest wins). */
    @Query("UPDATE memory SET category = :category, content = :content, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: Long, category: String, content: String, updatedAt: Long)

    /** Only the manual "clear" button in settings ever wipes memory. */
    @Query("DELETE FROM memory")
    suspend fun clear()
}
