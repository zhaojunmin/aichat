package com.octos.aichat.data.memory

import com.octos.aichat.ai.AiClient
import com.octos.aichat.ai.aiClientFor
import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.ai.recordAiFailure
import com.octos.aichat.data.AppSettings
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * The memory side of learning: what to ask the model about my recent replies, and how
 * to read its answer back into memory changes. The rules of memory — the categories,
 * keyword and importance bounds, add/update/delete — live here, next to the store
 * they shape; the model is only reached through [AiClient].
 *
 * [clientFor] and [localReady] are injected so learning can be unit-tested with a
 * fake client and without the native engine.
 */
class MemoryLearner(
    private val clientFor: (AppSettings) -> AiClient = ::aiClientFor,
    private val localReady: () -> Boolean = { LlmEngine.isReady },
) {

    /**
     * Distil my recent replies into memory changes. Memory is permanent and never
     * wiped: the learner may only ADD new items or OVERWRITE an existing row it
     * references by id (conflict → newest wins). Returns null on any failure so
     * the caller can retry later without losing the un-learned replies.
     */
    suspend fun learn(
        settings: AppSettings,
        existing: List<MemoryEntity>,
        replies: List<String>,
    ): MemoryLearnResult? = withContext(Dispatchers.IO) {
        if ((!settings.useLocal && settings.apiKey.isBlank()) || replies.isEmpty()) {
            return@withContext null
        }
        if (settings.useLocal && !localReady()) return@withContext null

        val system = "你是用户画像分析助手。从用户的历史发言中提炼稳定、长期有用的个人信息,维护一份干净、不矛盾的记忆库。"
        val user = buildString {
            append("已有记忆(带编号,可能为空):\n")
            append(
                if (existing.isEmpty()) {
                    "(无)"
                } else {
                    existing.joinToString("\n") { "#${it.id} 【${it.category}】${it.content}" }
                },
            )
            append("\n\n用户最近的发言(每行一条,均出自\"我\"):\n")
            append(replies.joinToString("\n") { "- $it" })
            append("\n\n请输出三类改动,不要改动与发言无关的已有记忆:")
            append("\n1. additions:发言里新出现、已有记忆中没有的稳定信息。")
            append("\n2. updates:与某条已有记忆矛盾或是它的更新,引用该条编号 id,用最新发言覆盖它。")
            append("\n3. deletions:已有记忆中明确不再成立、已作废的条目 id 列表(例如发言表明该事实已不存在,且无需替换)。")
            append("\n保守对待 deletions:仅在明确失效时删除;能用 updates 修正的优先用 updates,不确定就都不动。")
            append("\n只保留对\"模仿我的口吻、了解我的事实背景\"有用的稳定信息,忽略一次性闲聊和对方的事。")
            append("没有可提炼的信息时三个数组都返回空。")
            append("additions / updates 每条带:")
            append("\n- category:分类标签,取值限 画像、事实、偏好、风格。")
            append("\n- keywords:2-5 个便于日后检索的关键词,空格分隔(含同义词/相关实体)。")
            append("\n- importance:重要程度 1-10 整数(越稳定、越能代表本人取值越高)。")
            append("\n仅返回 JSON,形如 ")
            append("{\"additions\":[{\"category\":\"事实\",\"content\":\"在北京做程序员\",")
            append("\"keywords\":\"北京 程序员 工作\",\"importance\":8}],")
            append("\"updates\":[{\"id\":12,\"category\":\"偏好\",\"content\":\"现在改喝拿铁\",")
            append("\"keywords\":\"拿铁 咖啡 口味\",\"importance\":5}],")
            append("\"deletions\":[7]},不要多余文字。")
        }

        val raw = runCatching { clientFor(settings).complete(settings, system, user) }
            .onFailure { DiagLog.recordAiFailure(it) }
            .getOrNull()
            ?: return@withContext null
        parseLearnResult(raw) ?: run {
            DiagLog.record(ErrorCode.MEM_602)
            null
        }
    }

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
                    additions.add(
                        MemoryDraft(
                            normCategory(o.optString("category")),
                            content,
                            normKeywords(o.optString("keywords")),
                            normImportance(o.optInt("importance", 5)),
                        ),
                    )
                }
            }
            val updates = ArrayList<MemoryEdit>()
            obj.optJSONArray("updates")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optLong("id", -1L)
                    val content = o.optString("content").trim()
                    if (id < 0 || content.isEmpty()) continue
                    updates.add(
                        MemoryEdit(
                            id,
                            normCategory(o.optString("category")),
                            content,
                            normKeywords(o.optString("keywords")),
                            normImportance(o.optInt("importance", 5)),
                        ),
                    )
                }
            }
            val deletions = ArrayList<Long>()
            obj.optJSONArray("deletions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val id = arr.optLong(i, -1L)
                    if (id >= 0) deletions.add(id)
                }
            }
            MemoryLearnResult(additions, updates, deletions)
        } catch (_: JSONException) {
            null
        }
    }

    private fun normCategory(raw: String): String =
        raw.trim().let { if (it in CATEGORIES) it else DEFAULT_CATEGORY }

    /** Collapse whitespace; cap at a handful of terms so the column stays small. */
    private fun normKeywords(raw: String): String =
        raw.trim().split(Regex("[\\s,，、]+")).filter { it.isNotBlank() }.take(8).joinToString(" ")

    private fun normImportance(raw: Int): Int = raw.coerceIn(1, 10)

    companion object {
        /** The only categories a memory may carry; anything else is filed as [DEFAULT_CATEGORY]. */
        val CATEGORIES = setOf("画像", "事实", "偏好", "风格")
        const val DEFAULT_CATEGORY = "事实"
    }
}
