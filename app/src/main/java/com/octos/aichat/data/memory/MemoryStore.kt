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

    /**
     * Log my last message in [conv] — the newest thing I actually said in this chat —
     * into the reply log memory learns from. Suggestions I pick are not logged.
     */
    fun recordMyLastMessage(conv: Conversation)

    /**
     * Run a learning pass if enough replies are pending. Called when the user leaves
     * the chat app: in local mode, recording doesn't learn by itself (see [MemoryRepository]).
     */
    fun learnIfDue()
    fun memoryBlock(conv: Conversation? = null): String

    suspend fun learnNow()
    suspend fun deleteMemory(id: Long)
    suspend fun restoreMemory(id: Long)
    suspend fun purgeMemory(id: Long)
    suspend fun editMemory(id: Long, content: String)
    suspend fun clearAll()
}
