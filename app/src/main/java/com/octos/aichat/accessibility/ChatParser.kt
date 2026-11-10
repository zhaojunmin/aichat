package com.octos.aichat.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.Speaker

/**
 * Turns the on-screen accessibility node tree of a chat app into a [Conversation].
 *
 * Two strategies:
 *  1. **Precise (per-app)** — when we know the resource-id of the real message
 *     bubble for an app, collect only those nodes. This cleanly excludes profile
 *     cards, suggested-opener prompts, menus, etc. Verified for Soul:
 *     `cn.soulapp.android:id/content_text`.
 *  2. **Generic (fallback)** — for unknown apps, collect leaf text nodes in the
 *     vertical middle band (dropping the title bar and input/toolbar) and guess
 *     the author from the bubble's horizontal position.
 *
 * In both cases the author is inferred from horizontal position: the other
 * party hugs the left edge, I hug the right.
 */
object ChatParser {

    /** Per-app resource-ids that identify a genuine message bubble's text. */
    private val MESSAGE_IDS: Map<String, Set<String>> = mapOf(
        "cn.soulapp.android" to setOf("cn.soulapp.android:id/content_text"),
    )

    // Text we never want to treat as a chat message (generic path only).
    private val NOISE = setOf(
        "发送", "语音", "表情", "更多", "按住 说话", "按住说话", "返回",
        "拍摄", "相册", "视频通话", "语音通话", "输入", "聊天信息", "转账",
    )

    fun parse(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        pkg: String,
    ): Conversation {
        if (root == null || screenWidth <= 0) return Conversation(pkg, emptyList())

        val whitelist = MESSAGE_IDS[pkg]
        val collected = ArrayList<Pair<Rect, String>>()

        if (whitelist != null) {
            collectById(root, whitelist, collected)
        } else {
            collectLeafText(root, collected)
            applyVerticalBand(collected, screenHeight)
        }

        // Top-to-bottom, then left-to-right = chronological for a chat list.
        val ordered = collected.sortedWith(compareBy({ it.first.top }, { it.first.left }))

        val messages = ArrayList<ChatMessage>()
        var lastText: String? = null
        for ((rect, text) in ordered) {
            if (!looksLikeMessage(text)) continue
            if (text == lastText) continue // collapse duplicates from re-layout
            messages.add(ChatMessage(classify(rect, screenWidth), text))
            lastText = text
        }
        return Conversation(pkg, messages)
    }

    // ---- Precise path ----------------------------------------------------

    private fun collectById(
        node: AccessibilityNodeInfo?,
        ids: Set<String>,
        out: MutableList<Pair<Rect, String>>,
    ) {
        if (node == null) return
        val vid = node.viewIdResourceName
        if (vid != null && vid in ids) {
            val raw = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (!raw.isNullOrEmpty()) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                if (rect.width() > 0 && rect.height() > 0) out.add(rect to raw)
            }
        }
        for (i in 0 until node.childCount) collectById(node.getChild(i), ids, out)
    }

    // ---- Generic path ----------------------------------------------------

    private fun collectLeafText(node: AccessibilityNodeInfo?, out: MutableList<Pair<Rect, String>>) {
        if (node == null) return
        if (node.childCount == 0) {
            val raw = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (!raw.isNullOrEmpty()) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                if (rect.width() > 0 && rect.height() > 0) out.add(rect to raw)
            }
        }
        for (i in 0 until node.childCount) collectLeafText(node.getChild(i), out)
    }

    /** Keep only the scrollable message band, dropping title bar and input bar. */
    private fun applyVerticalBand(items: MutableList<Pair<Rect, String>>, screenHeight: Int) {
        if (screenHeight <= 0) return
        val top = screenHeight * 0.09f
        val bottom = screenHeight * 0.90f
        items.retainAll { it.first.centerY().toFloat() in top..bottom }
    }

    private fun looksLikeMessage(text: String): Boolean {
        if (text.length > 500) return false
        if (text in NOISE) return false
        // Typing indicators are not real messages.
        if (text.contains("正在输入")) return false
        // WeChat system notices (recall / join-group) are not chat content.
        if (text.contains("撤回了一条消息")) return false
        if (text.endsWith("加入了群聊") || text.endsWith("加入群聊")) return false
        // Pure timestamps like "12:34" or "昨天 20:11" are list separators, skip.
        if (text.matches(Regex("""^(上午|下午|昨天|今天)?\s*\d{1,2}:\d{2}$"""))) return false
        return true
    }

    private fun classify(rect: Rect, screenWidth: Int): Speaker {
        val leftZone = screenWidth * 0.18f
        val rightZone = screenWidth * 0.82f
        return when {
            rect.right >= rightZone && rect.left > leftZone -> Speaker.ME
            rect.left <= leftZone && rect.right < rightZone -> Speaker.OTHER
            // Ambiguous (full-width): fall back to which half the center sits in.
            rect.centerX() >= screenWidth / 2 -> Speaker.ME
            else -> Speaker.OTHER
        }
    }
}
