package com.octos.aichat.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the app settings so ViewModels depend on this (and can be
 * unit-tested with a fake) rather than the concrete [SettingsRepository] singleton.
 */
interface SettingsStore {
    val state: StateFlow<AppSettings>
    fun current(): AppSettings
    fun update(transform: (AppSettings) -> AppSettings)
}
