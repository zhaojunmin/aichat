package com.octos.aichat.data

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
    val title: String,
    val systemPrompt: String,
)

object PersonaPresets {
    val DEFAULTS: List<PersonaPreset> = listOf(
        PersonaPreset(
            id = "dating",
            title = "恋爱/暧昧",
            systemPrompt = "你是一个高情商的恋爱聊天助手,帮我回复暧昧对象的消息。" +
                "风格要自然、有趣、略带调侃和暖意,避免油腻和肉麻,不要用力过猛。" +
                "根据对方语气灵活应变,适当抛出话题延续对话。"
        ),
        PersonaPreset(
            id = "icebreak",
            title = "社交破冰",
            systemPrompt = "你是社交聊天助手,帮我在刚认识的人之间破冰。" +
                "回复要礼貌、轻松、好接话,结尾可带一个开放式问题引导对方继续聊。"
        ),
        PersonaPreset(
            id = "business",
            title = "商务/正式",
            systemPrompt = "你是专业的商务沟通助手,帮我回复工作与商务消息。" +
                "语气得体、简洁、专业,表达清晰的态度和下一步安排,避免口语化和表情。"
        ),
        PersonaPreset(
            id = "support",
            title = "客服/答疑",
            systemPrompt = "你是耐心友好的客服助手,帮我回复用户咨询。" +
                "先共情再解决,给出明确、可执行的答复,语气温和专业。"
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
    val autoPaste: Boolean = false,
    /** Read chat via screenshot + OCR instead of the accessibility node tree. */
    val useScreenshotOcr: Boolean = false,
    /** Only these packages use OCR; everything else keeps node-tree parsing. */
    val ocrPackages: String = DEFAULT_OCR_PACKAGES,
    /** Comma-separated package names we offer suggestions inside. */
    val targetPackages: String = DEFAULT_TARGET_PACKAGES,
) {
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
        // WeChat + Soul + Hertz out of the box.
        const val DEFAULT_TARGET_PACKAGES = "com.tencent.mm,cn.soulapp.android,com.immomo.vchat"
        // Only WeChat needs OCR; Soul and Hertz are parsed from the node tree.
        const val DEFAULT_OCR_PACKAGES = "com.tencent.mm"

        // Always-supported apps, merged with the user's list.
        val BUILTIN_TARGETS = setOf("com.tencent.mm", "cn.soulapp.android", "com.immomo.vchat")
        val BUILTIN_OCR = setOf("com.tencent.mm")
    }
}
