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
        backend = AiBackend.fromName(prefs.getString(KEY_BACKEND, null)),
        backendConfigs = loadBackendConfigs(),
        personaId = prefs.getString(KEY_PERSONA_ID, "dating")!!,
        customPersona = prefs.getString(KEY_CUSTOM_PERSONA, "")!!,
        suggestionCount = prefs.getInt(KEY_SUGGESTION_COUNT, 3),
        autoPaste = prefs.getBoolean(KEY_AUTO_PASTE, false),
        useScreenshotOcr = prefs.getBoolean(KEY_USE_OCR, false),
        ocrPackages = loadOcrPackages(),
        targetPackages = prefs.getString(KEY_TARGETS, AppSettings.DEFAULT_TARGET_PACKAGES)!!,
        memoryEnabled = prefs.getBoolean(KEY_MEMORY_ENABLED, true),
        learnThreshold = prefs.getInt(KEY_LEARN_THRESHOLD, 20),
        memoryInjectLimit = prefs.getInt(KEY_INJECT_LIMIT, 30),
    )

    // Hertz moved from OCR to precise node-tree parsing; strip the now-stale
    // entry a previous version may have persisted into the user's OCR list.
    private fun loadOcrPackages(): String =
        prefs.getString(KEY_OCR_PKGS, AppSettings.DEFAULT_OCR_PACKAGES)!!
            .split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it != "com.immomo.vchat" }
            .joinToString(",")

    private fun loadBackendConfigs(): Map<AiBackend, BackendConfig> =
        AiBackend.entries.mapNotNull { b ->
            val baseUrl = prefs.getString(baseUrlPref(b), null)
            val model = prefs.getString(modelPref(b), null)
            // Legacy single key migrates onto DeepSeek the first time around.
            val legacyKey = if (b == AiBackend.DEEPSEEK) prefs.getString(KEY_API_KEY, "")!! else ""
            val apiKey = prefs.getString(apiKeyPref(b), "")!!.ifEmpty { legacyKey }
            if (baseUrl == null && model == null && apiKey.isEmpty()) null
            else b to BackendConfig(
                baseUrl = baseUrl ?: b.defaultBaseUrl,
                model = model ?: b.defaultModel,
                apiKey = apiKey,
            )
        }.toMap()

    private fun persist(s: AppSettings) {
        prefs.edit()
            .putString(KEY_BACKEND, s.backend.name)
            .apply {
                AiBackend.entries.forEach { b ->
                    val c = s.configOf(b)
                    putString(baseUrlPref(b), c.baseUrl)
                    putString(modelPref(b), c.model)
                    putString(apiKeyPref(b), c.apiKey)
                }
            }
            .putString(KEY_PERSONA_ID, s.personaId)
            .putString(KEY_CUSTOM_PERSONA, s.customPersona)
            .putInt(KEY_SUGGESTION_COUNT, s.suggestionCount)
            .putBoolean(KEY_AUTO_PASTE, s.autoPaste)
            .putBoolean(KEY_USE_OCR, s.useScreenshotOcr)
            .putString(KEY_OCR_PKGS, s.ocrPackages)
            .putString(KEY_TARGETS, s.targetPackages)
            .putBoolean(KEY_MEMORY_ENABLED, s.memoryEnabled)
            .putInt(KEY_LEARN_THRESHOLD, s.learnThreshold)
            .putInt(KEY_INJECT_LIMIT, s.memoryInjectLimit)
            .apply()
    }

    private fun baseUrlPref(b: AiBackend) = "${KEY_BASE_URL}_${b.name}"
    private fun modelPref(b: AiBackend) = "${KEY_MODEL}_${b.name}"
    private fun apiKeyPref(b: AiBackend) = "${KEY_API_KEY}_${b.name}"

    companion object {
        private const val PREFS = "ai_chat_settings"
        private const val KEY_BACKEND = "backend"
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
        private const val KEY_MEMORY_ENABLED = "memory_enabled"
        private const val KEY_LEARN_THRESHOLD = "learn_threshold"
        private const val KEY_INJECT_LIMIT = "memory_inject_limit"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context).also { INSTANCE = it }
            }
    }
}
