package com.octos.aichat.ai

import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

/**
 * Turns an extracted [Conversation] into a handful of candidate replies via the
 * configured OpenAI-compatible chat API (DeepSeek / Doubao).
 */
object AiRepository {

    private val client: AiClient = OpenAiCompatClient()

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
            append("聊天记录(我/对方):\n\n")
            append(transcript.ifBlank { "(暂无可识别的聊天内容)" })
            append("\n\n以\"我\"的口吻给 ").append(n).append(" 条风格各异的回复。")
            append("要求:每条一句话、像微信随手回,20 字左右、最多 40 字;")
            append("口语化、直接能发,别客套、别铺垫、别解释、别重复对方的话。")
            append("仅返回 JSON 字符串数组,如 [\"回复1\",\"回复2\"]。")
        }

        val system = settings.effectiveSystemPrompt() + " 回复务必简短,一句话说清,不堆砌、不解释。"
        val raw = client.complete(settings, system, user)

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
