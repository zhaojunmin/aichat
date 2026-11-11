package com.octos.aichat.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for [AppSettings], backed by SharedPreferences so that
 * both the Compose UI and the background services can read it synchronously.
 * Exposes a [StateFlow] so the UI recomposes on change.
 */
class SettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state

    /** Latest snapshot, safe to call from any thread. */
    fun current(): AppSettings = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        persist(next)
        _state.value = next
    }

    private fun load(): AppSettings = AppSettings(
        baseUrl = prefs.getString(KEY_BASE_URL, AppSettings.DEFAULT_DEEPSEEK_BASE_URL)!!,
        apiKey = prefs.getString(KEY_API_KEY, "")!!,
        model = prefs.getString(KEY_MODEL, AppSettings.DEFAULT_DEEPSEEK_MODEL)!!,
        personaId = prefs.getString(KEY_PERSONA_ID, "dating")!!,
        customPersona = prefs.getString(KEY_CUSTOM_PERSONA, "")!!,
        suggestionCount = prefs.getInt(KEY_SUGGESTION_COUNT, 3),
        autoPaste = prefs.getBoolean(KEY_AUTO_PASTE, false),
        useScreenshotOcr = prefs.getBoolean(KEY_USE_OCR, false),
        ocrPackages = prefs.getString(KEY_OCR_PKGS, AppSettings.DEFAULT_OCR_PACKAGES)!!,
        targetPackages = prefs.getString(KEY_TARGETS, AppSettings.DEFAULT_TARGET_PACKAGES)!!,
    )

    private fun persist(s: AppSettings) {
        prefs.edit()
            .putString(KEY_BASE_URL, s.baseUrl)
            .putString(KEY_API_KEY, s.apiKey)
            .putString(KEY_MODEL, s.model)
            .putString(KEY_PERSONA_ID, s.personaId)
            .putString(KEY_CUSTOM_PERSONA, s.customPersona)
            .putInt(KEY_SUGGESTION_COUNT, s.suggestionCount)
            .putBoolean(KEY_AUTO_PASTE, s.autoPaste)
            .putBoolean(KEY_USE_OCR, s.useScreenshotOcr)
            .putString(KEY_OCR_PKGS, s.ocrPackages)
            .putString(KEY_TARGETS, s.targetPackages)
            .apply()
    }

    companion object {
        private const val PREFS = "ai_chat_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_PERSONA_ID = "persona_id"
        private const val KEY_CUSTOM_PERSONA = "custom_persona"
        private const val KEY_SUGGESTION_COUNT = "suggestion_count"
        private const val KEY_AUTO_PASTE = "auto_paste"
        private const val KEY_USE_OCR = "use_ocr"
        private const val KEY_OCR_PKGS = "ocr_packages"
        private const val KEY_TARGETS = "target_packages"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
    }
}
