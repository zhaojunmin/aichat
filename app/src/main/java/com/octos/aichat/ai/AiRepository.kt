package com.octos.aichat.ai

import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.memory.MemoryDraft
import com.octos.aichat.data.memory.MemoryEdit
import com.octos.aichat.data.memory.MemoryEntity
import com.octos.aichat.data.memory.MemoryLearnResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns an extracted [Conversation] into a handful of candidate replies via the
 * configured OpenAI-compatible chat API (DeepSeek / Doubao).
 */
object AiRepository {

    private val client: AiClient = OpenAiCompatClient()

    suspend fun generateReplies(
        settings: AppSettings,
        conversation: Conversation,
        memoryBlock: String = "",
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

        val system = buildString {
            append(settings.effectiveSystemPrompt())
            append(" 回复务必简短,一句话说清,不堆砌、不解释。")
            if (memoryBlock.isNotBlank()) {
                append("\n\n关于\"我\"的已知信息(用来让回复更像我本人,自然运用、别生硬套用):\n")
                append(memoryBlock)
            }
        }
        val raw = client.complete(settings, system, user)

        parseReplies(raw, n)
    }

    /**
     * Distil my recent replies into memory changes. Memory is permanent and never
     * wiped: the learner may only ADD new items or OVERWRITE an existing row it
     * references by id (conflict → newest wins). Returns null on any failure so
     * the caller can retry later without losing the un-learned replies.
     */
    suspend fun learnMemory(
        settings: AppSettings,
        existing: List<MemoryEntity>,
        replies: List<String>,
    ): MemoryLearnResult? = withContext(Dispatchers.IO) {
        if (settings.apiKey.isBlank() || replies.isEmpty()) return@withContext null

        val system = "你是用户画像分析助手。从用户的历史发言中提炼稳定、长期有用的个人信息,维护一份只增不删的记忆库。"
        val user = buildString {
            append("已有记忆(带编号,可能为空):\n")
            append(
                if (existing.isEmpty()) "(无)"
                else existing.joinToString("\n") { "#${it.id} 【${it.category}】${it.content}" }
            )
            append("\n\n用户最近的发言(每行一条,均出自\"我\"):\n")
            append(replies.joinToString("\n") { "- $it" })
            append("\n\n请只输出两类改动,不要删除、也不要重写与发言无关的已有记忆:")
            append("\n1. additions:发言里新出现、已有记忆中没有的稳定信息。")
            append("\n2. updates:与某条已有记忆矛盾或是它的更新,引用该条编号 id,用最新发言覆盖它。")
            append("\n只保留对\"模仿我的口吻、了解我的事实背景\"有用的稳定信息,忽略一次性闲聊和对方的事。")
            append("没有可提炼的信息时两个数组都返回空。")
            append("每条记忆带一个分类标签,取值限:画像、事实、偏好、风格。")
            append("仅返回 JSON,形如 ")
            append("{\"additions\":[{\"category\":\"事实\",\"content\":\"在北京做程序员\"}],")
            append("\"updates\":[{\"id\":12,\"category\":\"偏好\",\"content\":\"现在改喝拿铁\"}]},不要多余文字。")
        }

        val raw = runCatching { client.complete(settings, system, user) }.getOrNull() ?: return@withContext null
        parseLearnResult(raw)
    }

    private val MEMORY_CATEGORIES = setOf("画像", "事实", "偏好", "风格")

    /** Tolerant parse of the learner's {additions:[...], updates:[...]} object. */
    internal fun parseLearnResult(raw: String): MemoryLearnResult? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start !in 0 until end) return null
        return try {
            val obj = JSONObject(cleaned.substring(start, end + 1))
            val additions = ArrayList<MemoryDraft>()
            obj.optJSONArray("additions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val content = o.optString("content").trim()
                    if (content.isEmpty()) continue
                    additions.add(MemoryDraft(normCategory(o.optString("category")), content))
                }
            }
            val updates = ArrayList<MemoryEdit>()
            obj.optJSONArray("updates")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optLong("id", -1L)
                    val content = o.optString("content").trim()
                    if (id < 0 || content.isEmpty()) continue
                    updates.add(MemoryEdit(id, normCategory(o.optString("category")), content))
                }
            }
            MemoryLearnResult(additions, updates)
        } catch (_: JSONException) {
            null
        }
    }

    private fun normCategory(raw: String): String =
        raw.trim().let { if (it in MEMORY_CATEGORIES) it else "事实" }

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
