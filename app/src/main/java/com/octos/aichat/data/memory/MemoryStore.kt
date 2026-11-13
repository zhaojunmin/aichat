package com.octos.aichat.data.memory

import com.octos.aichat.data.Conversation
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over long-term memory so ViewModels depend on this (and can be
 * unit-tested with a fake) rather than the concrete [MemoryRepository] singleton.
 */
interface MemoryStore {
    val memories: StateFlow<List<MemoryEntity>>
    val deleted: StateFlow<List<MemoryEntity>>
    val stats: StateFlow<MemoryStats>

    fun recordMyMessages(conv: Conversation)
    fun recordReply(text: String)
    fun memoryBlock(conv: Conversation? = null): String

    suspend fun learnNow()
    suspend fun deleteMemory(id: Long)
    suspend fun restoreMemory(id: Long)
    suspend fun purgeMemory(id: Long)
    suspend fun editMemory(id: Long, content: String)
    suspend fun clearAll()
}
