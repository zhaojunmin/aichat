package com.octos.aichat.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.SettingsStore
import com.octos.aichat.data.memory.MemoryRepository
import com.octos.aichat.data.memory.MemoryStore

/**
 * State for the advanced-settings screen. Depends on [SettingsStore] / [MemoryStore]
 * abstractions, so the composable no longer reaches for the repository singletons.
 */
class AdvancedViewModel(
    private val settings: SettingsStore,
    memory: MemoryStore,
) : ViewModel() {

    val settingsState = settings.state
    val stats = memory.stats
    val memories = memory.memories

    fun update(transform: (AppSettings) -> AppSettings) = settings.update(transform)

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer { AdvancedViewModel(SettingsRepository.get(context), MemoryRepository.get(context)) }
        }
    }
}
