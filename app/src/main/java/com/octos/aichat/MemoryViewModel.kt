package com.octos.aichat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octos.aichat.data.memory.MemoryRepository
import com.octos.aichat.data.memory.MemoryStore
import kotlinx.coroutines.launch

/**
 * Holds the memory-management screen's state + actions, depending on [MemoryStore]
 * (an abstraction, injected) rather than the concrete repository. Side-effects run
 * in [viewModelScope] so the composable stays free of coroutine plumbing.
 */
class MemoryViewModel(private val memory: MemoryStore) : ViewModel() {

    val memories = memory.memories
    val deleted = memory.deleted

    fun edit(id: Long, content: String) = viewModelScope.launch { memory.editMemory(id, content) }
    fun delete(id: Long) = viewModelScope.launch { memory.deleteMemory(id) }
    fun restore(id: Long) = viewModelScope.launch { memory.restoreMemory(id) }
    fun purge(id: Long) = viewModelScope.launch { memory.purgeMemory(id) }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { MemoryViewModel(MemoryRepository.get(context)) }
        }
    }
}
