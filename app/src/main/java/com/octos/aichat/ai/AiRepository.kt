package com.octos.aichat.ai

import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

/**
 * Turns an extracted [Conversation] into a handful of candidate replies using
 * whichever backend the user configured.
 */
object AiRepository {

    private fun clientFor(backend: AiBackend): AiClient = when (backend) {
        AiBackend.CLAUDE -> ClaudeClient()
        // DeepSeek is OpenAI-compatible, so it reuses the same client shape.
        AiBackend.DEEPSEEK, AiBackend.OPENAI -> OpenAiClient()
    }

    suspend fun generateReplies(
        settings: AppSettings,
        conversation: Conversation,
    ): List<String> = withContext(Dispatchers.IO) {
        require(settings.apiKey.isNotBlank()) { "尚未填写 API Key" }

        val n = settings.suggestionCount.coerceIn(1, 5)
        // Exclude my own trailing messages so the model replies to the other
        // party's latest line instead of continuing my last turn.
        val transcript = conversation.withoutTrailingMine().toTranscript()
        val user = buildString {
            append("下面是我和对方的聊天记录(我=我自己,对方=对方):\n\n")
            append(transcript.ifBlank { "(暂无可识别的聊天内容)" })
            append("\n\n请站在\"我\"的角度,生成 ").append(n).append(" 条不同风格的回复候选,")
            append("直接作为我的下一句话发出去,口语自然、长度适中。")
            append("只返回一个 JSON 字符串数组,例如 [\"回复1\",\"回复2\"],不要任何额外解释或代码块标记。")
        }

        val raw = clientFor(settings.backend)
            .complete(settings, settings.effectiveSystemPrompt(), user)

        parseReplies(raw, n)
    }

    /** Be tolerant: accept a JSON array, a fenced block, or newline-separated lines. */
    internal fun parseReplies(raw: String, max: Int): List<String> {
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
                if (out.isNotEmpty()) return out.take(max)
            } catch (_: JSONException) {
                // fall through to line parsing
            }
        }

        return cleaned.lines()
            .map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .map { it.replaceFirst(Regex("^\\d+[.、)]\\s*"), "") }
            .filter { it.isNotEmpty() }
            .take(max)
            .ifEmpty { listOf(cleaned) }
    }
}
