package com.octos.aichat.ai

import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException

/**
 * Turns an extracted [Conversation] into a handful of suggestions via the
 * configured OpenAI-compatible chat API (DeepSeek / Doubao).
 */
class AiService(
    // The user is waiting mid-chat: past this they'd rather type themselves.
    // Cloud only — a blocking on-device inference can't be interrupted midway.
    private val suggestionTimeoutMs: Long = SUGGESTION_TIMEOUT_MS,
    // Which AiClient to use for a given request — injected so suggestion generation and
    // learning can be unit-tested with a fake client. Default = cloud/local selection.
    // Last, so callers can pass it as a trailing lambda.
    private val clientFor: (AppSettings) -> AiClient = ::aiClientFor,
) {

    suspend fun generateSuggestions(
        settings: AppSettings,
        conversation: Conversation,
        memoryBlock: String = "",
    ): List<String> = withContext(Dispatchers.IO) {
        require(settings.useLocal || settings.apiKey.isNotBlank()) { "尚未填写 API Key" }

        val n = settings.suggestionCount.coerceIn(1, 5)
        // Exclude my own trailing messages so the suggestions answer the other
        // party's latest line instead of continuing my last turn.
        val transcript = conversation.withoutTrailingMine().toTranscript()
        val user = buildString {
            append("聊天记录(我/对方):\n\n")
            append(transcript.ifBlank { "(暂无可识别的聊天内容)" })
            append("\n\n以\"我\"的口吻给 ").append(n).append(" 条风格各异的回复。")
            append("要求:每条一句话、像微信随手回,20 字左右、最多 40 字;")
            append("口语化、直接能发,别客套、别铺垫、别解释、别重复对方的话。")
            // Output language follows the conversation, not the UI locale: suggest in
            // whatever language the other party is using (English chat -> English suggestions).
            append("用与对方消息相同的语言回复(对方说英文就用英文、中文就用中文)。")
            append("仅返回 JSON 字符串数组,如 [\"回复1\",\"回复2\"]。")
        }

        val system = buildString {
            append(settings.effectiveSystemPrompt())
            append(" 回复务必简短,一句话说清,不堆砌、不解释。")
            if (memoryBlock.isNotBlank()) {
                append("\n\n关于\"我\"的已知信息(用来让回复更像我本人,自然运用、别生硬套用):\n")
                append(memoryBlock)
            }
        }
        val client = clientFor(settings)
        val raw = if (settings.useLocal) {
            client.complete(settings, system, user)
        } else {
            // Timing out cancels the coroutine, which cancels the HTTP call.
            withTimeoutOrNull(suggestionTimeoutMs) { client.complete(settings, system, user) }
                ?: throw AiException(
                    AiException.Kind.TIMEOUT,
                    detail = "suggestion budget ${suggestionTimeoutMs}ms",
                )
        }

        // An empty or content-free answer must surface as an error, never as a blank candidate.
        parseSuggestions(raw, n).ifEmpty {
            throw AiException(
                AiException.Kind.BAD_RESPONSE,
                detail = "no suggestions in: ${raw.take(80)}",
            )
        }
    }

    /**
     * Be tolerant: accept a JSON array, a fenced block, or newline-separated lines.
     * Empty when the answer holds no usable suggestion (blank, `[]`, only blank items).
     */
    internal fun parseSuggestions(raw: String, max: Int): List<String> {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start in 0 until end) {
            try {
                val arr = JSONArray(cleaned.substring(start, end + 1))
                val out = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) out.add(s)
                }
                // A well-formed array is the answer, even if empty: falling back to
                // line parsing would surface the literal "[]" as a suggestion.
                return out.take(max)
            } catch (_: JSONException) {
                // fall through to line parsing
            }
        }

        return cleaned.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .map { it.replaceFirst(Regex("^\\d+[.、)]\\s*"), "") }
            .filter { it.isNotEmpty() }
            .take(max)
    }

    companion object {
        const val SUGGESTION_TIMEOUT_MS = 30_000L

        /** Production singleton with the real cloud/local client selection. */
        val instance: AiService by lazy { AiService() }
    }
}
