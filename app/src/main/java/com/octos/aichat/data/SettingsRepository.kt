package com.octos.aichat.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for [AppSettings], backed by SharedPreferences so that
 * both the Compose UI and the background services can read it synchronously.
 * Exposes a [StateFlow] so the UI recomposes on change.
 *
 * API keys are stored encrypted via [secrets] (Android Keystore); everything else
 * is plain preferences.
 */
class SettingsRepository private constructor(
    context: Context,
    private val secrets: SecretCodec = SecretCodec(KeystoreSecretCipher),
) : SettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Last (plain key -> stored value) per backend, so persist() — called on every
    // settings change, e.g. each keystroke — only hits the Keystore when a key changes.
    private val encodedKeys = HashMap<AiBackend, Pair<String, String>>()

    // Set by load() when a key was still stored in plaintext by an older version.
    private var foundPlaintextKey = false

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<AppSettings> = _state

    init {
        // Upgrade path: re-save so plaintext keys are replaced by ciphertext right away.
        if (foundPlaintextKey) persist(_state.value)
    }

    /** Latest snapshot, safe to call from any thread. */
    override fun current(): AppSettings = _state.value

    override fun update(transform: (AppSettings) -> AppSettings) {
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
        autoPaste = prefs.getBoolean(KEY_AUTO_PASTE, true),
        bubbleAutoStart = prefs.getBoolean(KEY_BUBBLE_AUTOSTART, true),
        ocrPackages = loadOcrPackages(),
        targetPackages = prefs.getString(KEY_TARGETS, AppSettings.DEFAULT_TARGET_PACKAGES)!!,
        memoryEnabled = prefs.getBoolean(KEY_MEMORY_ENABLED, true),
        learnThreshold = prefs.getInt(KEY_LEARN_THRESHOLD, 20),
        memoryInjectLimit = prefs.getInt(KEY_INJECT_LIMIT, 30),
        memoryEmbeddingEnabled = prefs.getBoolean(KEY_EMBED_ENABLED, true),
        inferenceMode = runCatching {
            InferenceMode.valueOf(prefs.getString(KEY_INFER_MODE, null) ?: "CLOUD")
        }.getOrDefault(InferenceMode.CLOUD),
        localModelId = prefs.getString(KEY_LOCAL_MODEL, null) ?: AppSettings.DEFAULT_LOCAL_MODEL_ID,
        themeVariantId = prefs.getString(KEY_THEME, null) ?: "system",
        diagnosticsEnabled = prefs.getBoolean(KEY_DIAGNOSTICS, true),
        diagnosticsEnabledAt = prefs.getLong(KEY_DIAGNOSTICS_SINCE, 0L),
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
            // Legacy single (plaintext) key migrates onto DeepSeek the first time around.
            val legacyKey = if (b == AiBackend.DEEPSEEK) prefs.getString(KEY_API_KEY, "")!! else ""
            val apiKey = loadApiKey(b).ifEmpty {
                if (legacyKey.isNotEmpty()) foundPlaintextKey = true
                legacyKey
            }
            if (baseUrl == null && model == null && apiKey.isEmpty()) {
                null
            } else {
                b to BackendConfig(
                    baseUrl = baseUrl ?: b.defaultBaseUrl,
                    model = model ?: b.defaultModel,
                    apiKey = apiKey,
                )
            }
        }.toMap()

    private fun loadApiKey(b: AiBackend): String {
        val stored = prefs.getString(apiKeyPref(b), null)
        val decoded = secrets.decode(stored)
        if (decoded.isLegacyPlaintext) {
            foundPlaintextKey = true
        } else if (stored != null && decoded.value.isNotEmpty()) {
            encodedKeys[b] = decoded.value to stored
        }
        return decoded.value
    }

    /**
     * Stored form of [plain] for backend [b]; null if encryption failed. On failure
     * the key is kept in memory only — never written in plaintext — so at worst the
     * user re-enters it after a restart.
     */
    private fun encodeApiKey(b: AiBackend, plain: String): String? {
        encodedKeys[b]?.let { (cachedPlain, stored) -> if (cachedPlain == plain) return stored }
        return try {
            secrets.encode(plain).also { encodedKeys[b] = plain to it }
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.SET_801, cause = e)
            null
        }
    }

    private fun persist(s: AppSettings) {
        prefs.edit {
            putString(KEY_BACKEND, s.backend.name)
            remove(KEY_API_KEY) // legacy plaintext single key; migrated in load()
            AiBackend.entries.forEach { b ->
                val c = s.configOf(b)
                putString(baseUrlPref(b), c.baseUrl)
                putString(modelPref(b), c.model)
                val key = encodeApiKey(b, c.apiKey)
                if (key != null) putString(apiKeyPref(b), key) else remove(apiKeyPref(b))
            }
            putString(KEY_PERSONA_ID, s.personaId)
            putString(KEY_CUSTOM_PERSONA, s.customPersona)
            putInt(KEY_SUGGESTION_COUNT, s.suggestionCount)
            putBoolean(KEY_AUTO_PASTE, s.autoPaste)
            putBoolean(KEY_BUBBLE_AUTOSTART, s.bubbleAutoStart)
            remove(KEY_USE_OCR) // retired: the OCR app list alone decides the read path
            putString(KEY_OCR_PKGS, s.ocrPackages)
            putString(KEY_TARGETS, s.targetPackages)
            putBoolean(KEY_MEMORY_ENABLED, s.memoryEnabled)
            putInt(KEY_LEARN_THRESHOLD, s.learnThreshold)
            putInt(KEY_INJECT_LIMIT, s.memoryInjectLimit)
            putBoolean(KEY_EMBED_ENABLED, s.memoryEmbeddingEnabled)
            putString(KEY_INFER_MODE, s.inferenceMode.name)
            putString(KEY_LOCAL_MODEL, s.localModelId)
            putString(KEY_THEME, s.themeVariantId)
            putBoolean(KEY_DIAGNOSTICS, s.diagnosticsEnabled)
            putLong(KEY_DIAGNOSTICS_SINCE, s.diagnosticsEnabledAt)
        }
    }

    private fun baseUrlPref(b: AiBackend) = "${KEY_BASE_URL}_${b.name}"
    private fun modelPref(b: AiBackend) = "${KEY_MODEL}_${b.name}"
    private fun apiKeyPref(b: AiBackend) = "${KEY_API_KEY}_${b.name}"

    companion object {
        private const val PREFS = "ai_chat_settings"
        private const val KEY_BACKEND = "backend"
        private const val KEY_DIAGNOSTICS = "diagnostics_enabled"
        private const val KEY_DIAGNOSTICS_SINCE = "diagnostics_enabled_at"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_PERSONA_ID = "persona_id"
        private const val KEY_CUSTOM_PERSONA = "custom_persona"
        private const val KEY_SUGGESTION_COUNT = "suggestion_count"
        private const val KEY_AUTO_PASTE = "auto_paste"
        private const val KEY_BUBBLE_AUTOSTART = "bubble_auto_start"
        private const val KEY_USE_OCR = "use_ocr"
        private const val KEY_OCR_PKGS = "ocr_packages"
        private const val KEY_TARGETS = "target_packages"
        private const val KEY_MEMORY_ENABLED = "memory_enabled"
        private const val KEY_LEARN_THRESHOLD = "learn_threshold"
        private const val KEY_INJECT_LIMIT = "memory_inject_limit"
        private const val KEY_EMBED_ENABLED = "memory_embedding_enabled"
        private const val KEY_INFER_MODE = "inference_mode"
        private const val KEY_LOCAL_MODEL = "local_model_id"
        private const val KEY_THEME = "theme_variant_id"

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository = instance ?: synchronized(this) {
            instance ?: SettingsRepository(context).also { instance = it }
        }
    }
}
