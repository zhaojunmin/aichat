package com.octos.aichat.data

import androidx.annotation.StringRes
import com.octos.aichat.R

/**
 * Which model provider to call. Both speak the OpenAI-compatible
 * chat-completions shape, so they share one HTTP client; the backend only
 * carries the default Base URL and model name.
 *
 * Doubao = Volcengine Ark; its "model" is either an Ark inference endpoint id
 * (ep-...) or a model name like doubao-pro-32k.
 */
enum class AiBackend(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    DOUBAO("豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k");

    companion object {
        fun fromName(name: String?): AiBackend =
            entries.firstOrNull { it.name == name } ?: DEEPSEEK
    }
}

/** Where inference runs: the cloud API, or the on-device model (privacy mode). */
enum class InferenceMode { CLOUD, LOCAL }

/** Per-backend connection settings, so each provider keeps its own values. */
data class BackendConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
)

/**
 * A reusable "persona + scene" preset that shapes how the assistant replies.
 * The [systemPrompt] is sent as the system message; it is combined with the
 * extracted conversation to produce candidate replies.
 */
data class PersonaPreset(
    val id: String,
    @StringRes val titleRes: Int,
    val systemPrompt: String,
)

object PersonaPresets {
    val DEFAULTS: List<PersonaPreset> = listOf(
        PersonaPreset(
            id = "dating",
            titleRes = R.string.persona_dating,
            systemPrompt = "高情商恋爱助手。回复自然有趣、略带调侃和暖意,不油腻、不肉麻,顺势接话题。"
        ),
        PersonaPreset(
            id = "icebreak",
            titleRes = R.string.persona_icebreak,
            systemPrompt = "社交破冰助手。回复礼貌轻松、好接话,结尾可带一个开放式问题。"
        ),
        PersonaPreset(
            id = "business",
            titleRes = R.string.persona_business,
            systemPrompt = "商务沟通助手。语气得体专业,态度清晰、给出下一步,不口语化、不用表情。"
        ),
        PersonaPreset(
            id = "support",
            titleRes = R.string.persona_support,
            systemPrompt = "客服助手。先共情再解决,答复明确可执行,语气温和专业。"
        ),
    )
}

/** Full user configuration, persisted via [SettingsRepository]. */
data class AppSettings(
    val backend: AiBackend = AiBackend.DEEPSEEK,
    /** Base URL / model / key per backend, so switching providers keeps them apart. */
    val backendConfigs: Map<AiBackend, BackendConfig> = emptyMap(),
    val personaId: String = "dating",
    val customPersona: String = "",
    val suggestionCount: Int = 3,
    val autoPaste: Boolean = true,
    /** Auto-start the floating bubble on app launch (when overlay permission is granted). */
    val bubbleAutoStart: Boolean = true,
    /** Read chat via screenshot + OCR instead of the accessibility node tree. */
    val useScreenshotOcr: Boolean = false,
    /** Only these packages use OCR; everything else keeps node-tree parsing. */
    val ocrPackages: String = DEFAULT_OCR_PACKAGES,
    /** Comma-separated package names we offer suggestions inside. */
    val targetPackages: String = DEFAULT_TARGET_PACKAGES,
    /** Global memory: log my replies and learn a user profile the AI reuses. */
    val memoryEnabled: Boolean = true,
    /** Learn once this many new replies have piled up since the last run. */
    val learnThreshold: Int = 20,
    /** Inject only the most-recently-updated N memory items into the prompt. */
    val memoryInjectLimit: Int = 30,
    /** Use on-device semantic embeddings for memory recall (falls back to lexical when off/unavailable). */
    val memoryEmbeddingEnabled: Boolean = true,
    /** Cloud API vs on-device model. Local keeps chat content on the phone. */
    val inferenceMode: InferenceMode = InferenceMode.CLOUD,
    /** Which on-device model is selected (id of a [com.octos.aichat.ai.local.LocalModel]). */
    val localModelId: String = com.octos.aichat.ai.local.LocalModel.DEFAULT.id,
) {
    val useLocal: Boolean get() = inferenceMode == InferenceMode.LOCAL
    val localModel: com.octos.aichat.ai.local.LocalModel
        get() = com.octos.aichat.ai.local.LocalModel.of(localModelId)
    // Built-in supported apps are always included (union with user list), so a
    // newly supported app works without the user re-editing saved settings.
    val targetPackageSet: Set<String>
        get() = BUILTIN_TARGETS + targetPackages.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val ocrPackageSet: Set<String>
        get() = BUILTIN_OCR + ocrPackages.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Config of the given backend, falling back to that backend's defaults. */
    fun configOf(b: AiBackend): BackendConfig =
        backendConfigs[b] ?: BackendConfig(b.defaultBaseUrl, b.defaultModel)

    // Connection values for the currently selected backend.
    val baseUrl: String get() = configOf(backend).baseUrl
    val model: String get() = configOf(backend).model
    val apiKey: String get() = configOf(backend).apiKey

    private fun withConfig(transform: (BackendConfig) -> BackendConfig): AppSettings =
        copy(backendConfigs = backendConfigs + (backend to transform(configOf(backend))))

    fun withBaseUrl(v: String) = withConfig { it.copy(baseUrl = v) }
    fun withModel(v: String) = withConfig { it.copy(model = v) }
    fun withApiKey(v: String) = withConfig { it.copy(apiKey = v) }

    /** The effective system prompt: a custom one overrides the chosen preset. */
    fun effectiveSystemPrompt(): String {
        if (customPersona.isNotBlank()) return customPersona.trim()
        val preset = PersonaPresets.DEFAULTS.firstOrNull { it.id == personaId }
        return preset?.systemPrompt ?: PersonaPresets.DEFAULTS.first().systemPrompt
    }

    companion object {
        // WeChat + QQ + Soul + Hertz + Momo out of the box.
        const val DEFAULT_TARGET_PACKAGES =
            "com.tencent.mm,com.tencent.mobileqq,cn.soulapp.android,com.immomo.vchat,com.immomo.momo"
        // Screenshot-OCR apps: WeChat (whole-screen) and Momo (bubble-geometry
        // guided). QQ/Soul/Hertz are read straight from the node tree's text.
        const val DEFAULT_OCR_PACKAGES = "com.tencent.mm,com.immomo.momo"

        // Always-supported apps, merged with the user's list.
        val BUILTIN_TARGETS =
            setOf(
                "com.tencent.mm", "com.tencent.mobileqq", "cn.soulapp.android",
                "com.immomo.vchat", "com.immomo.momo",
            )
        val BUILTIN_OCR = setOf("com.tencent.mm", "com.immomo.momo")
    }
}
