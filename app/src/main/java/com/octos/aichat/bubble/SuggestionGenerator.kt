package com.octos.aichat.bubble

import com.octos.aichat.ai.AiClient
import com.octos.aichat.ai.AiException
import com.octos.aichat.ai.aiClientFor
import com.octos.aichat.ai.recordAiFailure
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.memory.MemoryStore
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.TimedOp
import com.octos.aichat.diagnostics.Tracing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException

/**
 * Turns the chat on screen into a handful of suggestions: builds the prompt, asks the
 * model through [AiClient] (cloud or on-device, per settings), enforces the cloud time
 * budget and parses the answer. The AI layer is reached only through [AiClient].
 */
class SuggestionGenerator(
    // The user is waiting mid-chat: past this they'd rather type themselves.
    // Cloud only — a blocking on-device inference can't be interrupted midway.
    private val suggestionTimeoutMs: Long = SUGGESTION_TIMEOUT_MS,
    // Which AiClient to use for a given request — injected so generation can be
    // unit-tested with a fake client. Default = cloud/local selection.
    // Last, so callers can pass it as a trailing lambda.
    private val clientFor: (AppSettings) -> AiClient = ::aiClientFor,
) {

    /**
     * One tap's suggestions for [conv]: log my on-screen messages into [memory], add what it
     * has learned about me to the prompt, then [generate] — traced and timed. A failure is
     * recorded for diagnostics and rethrown; cancellation (a newer tap) passes through.
     */
    suspend fun suggestFor(
        settings: AppSettings,
        conv: Conversation,
        memory: MemoryStore,
    ): List<String> {
        memory.recordMyMessages(conv)
        // Off the main thread: with semantic memory on, this runs a bge query embedding.
        val memoryBlock = withContext(Dispatchers.Default) {
            Tracing.section(Tracing.BUBBLE_MEMORY_BLOCK) { memory.memoryBlock(conv) }
        }
        val op = if (settings.useLocal) TimedOp.GENERATE_LOCAL else TimedOp.GENERATE_CLOUD
        return try {
            Tracing.asyncSection(Tracing.BUBBLE_GENERATE) {
                DiagLog.timed(op) { generate(settings, conv, memoryBlock) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DiagLog.recordAiFailure(e)
            throw e
        }
    }

    suspend fun generate(
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
    }
}
