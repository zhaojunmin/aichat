package com.octos.aichat.data

/** Which upstream chat-completion API shape to speak. */
enum class AiBackend(val displayName: String) {
    CLAUDE("Claude (Anthropic)"),
    DEEPSEEK("DeepSeek"),
    OPENAI("OpenAI 兼容");

    companion object {
        fun fromName(name: String?): AiBackend =
            entries.firstOrNull { it.name == name } ?: CLAUDE
    }
}

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
    val backend: AiBackend = AiBackend.CLAUDE,
    val baseUrl: String = DEFAULT_CLAUDE_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_CLAUDE_MODEL,
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

    /** The effective system prompt: a custom one overrides the chosen preset. */
    fun effectiveSystemPrompt(): String {
        if (customPersona.isNotBlank()) return customPersona.trim()
        val preset = PersonaPresets.DEFAULTS.firstOrNull { it.id == personaId }
        return preset?.systemPrompt ?: PersonaPresets.DEFAULTS.first().systemPrompt
    }

    companion object {
        const val DEFAULT_CLAUDE_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-4-8"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o"
        // DeepSeek speaks the OpenAI chat-completions shape.
        const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"
        // WeChat + Soul + Hertz out of the box.
        const val DEFAULT_TARGET_PACKAGES = "com.tencent.mm,cn.soulapp.android,com.immomo.vchat"
        // WeChat + Hertz use OCR by default; Soul keeps precise node-tree parsing.
        const val DEFAULT_OCR_PACKAGES = "com.tencent.mm,com.immomo.vchat"

        // Always-supported apps, merged with the user's list.
        val BUILTIN_TARGETS = setOf("com.tencent.mm", "cn.soulapp.android", "com.immomo.vchat")
        val BUILTIN_OCR = setOf("com.tencent.mm", "com.immomo.vchat")
    }
}
