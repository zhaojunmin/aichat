package com.octos.aichat.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.ChatReading
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.Speaker
import com.octos.aichat.data.SupportedApps

/**
 * Turns the on-screen accessibility node tree of a chat app into a [Conversation].
 *
 * Two strategies:
 *  1. **Precise (per-app rule, from [SupportedApps])** — when we know an app's message id, we
 *     collect only those text nodes (excluding profile cards, suggested openers,
 *     menus…). The author is read from the row's avatar position, which is
 *     pinned to the left (other) or right (me) regardless of message length —
 *     far more reliable than the text's horizontal center, which fails for long
 *     bubbles that span most of the width. Verified for Soul.
 *  2. **Generic (fallback)** — for unknown apps, collect leaf text nodes in the
 *     vertical middle band and guess the author from the bubble's position.
 */
object ChatParser {

    private data class Raw(val rect: Rect, val text: String, val speaker: Speaker?)

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

        val rule = SupportedApps.nodeTreeRule(pkg)
        val raws = ArrayList<Raw>()

        if (rule != null) {
            collectByRule(root, rule, screenWidth, raws)
        }
        // Generic path — used when the app has no rule, or when its rule matched
        // nothing (e.g. QQ updated and its obfuscated view-id changed). Degrading
        // to position-based parsing beats returning an empty conversation.
        if (raws.isEmpty()) {
            val leaves = ArrayList<Pair<Rect, String>>()
            collectLeafText(root, leaves)
            applyVerticalBand(leaves, screenHeight)
            leaves.mapTo(raws) { Raw(it.first, it.second, null) }
        }

        // Top-to-bottom, then left-to-right = chronological for a chat list.
        val ordered = raws.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))

        val messages = ArrayList<ChatMessage>()
        var lastText: String? = null
        for (raw in ordered) {
            if (!looksLikeMessage(raw.text)) continue
            if (raw.text == lastText) continue // collapse duplicates from re-layout
            val speaker = raw.speaker ?: classify(raw.rect, screenWidth)
            messages.add(ChatMessage(speaker, raw.text))
            lastText = raw.text
        }
        return Conversation(pkg, messages)
    }

    // ---- Precise path ----------------------------------------------------

    private fun collectByRule(
        node: AccessibilityNodeInfo?,
        rule: ChatReading.NodeTree,
        screenWidth: Int,
        out: MutableList<Raw>,
    ) {
        if (node == null) return
        val id = node.viewIdResourceName?.substringAfterLast('/')
        val speaker: Speaker? = when (id) {
            null -> null
            rule.selfId -> Speaker.ME
            rule.othersId -> Speaker.OTHER
            rule.messageId -> rule.avatarId?.let { senderFromAvatar(node, it, screenWidth) }
            else -> null
        }
        val isMessage = id != null &&
            (id == rule.selfId || id == rule.othersId || id == rule.messageId)
        if (isMessage) {
            val raw = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (!raw.isNullOrEmpty()) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                if (rect.width() > 0 && rect.height() > 0) {
                    out.add(Raw(rect, raw, speaker))
                }
            }
        }
        for (i in 0 until node.childCount) collectByRule(node.getChild(i), rule, screenWidth, out)
    }

    /**
     * Walk up to the message row, then find the avatar inside it; the avatar's
     * horizontal center tells us the author. Returns null if no avatar is found
     * (the caller then falls back to position-based classification).
     */
    private fun senderFromAvatar(
        messageNode: AccessibilityNodeInfo,
        avatarId: String,
        screenWidth: Int,
    ): Speaker? {
        var row: AccessibilityNodeInfo = messageNode
        var cur: AccessibilityNodeInfo? = messageNode.parent
        var hops = 0
        while (cur != null && hops < 8) {
            val r = Rect().also { cur!!.getBoundsInScreen(it) }
            if (r.width() > screenWidth * 0.85f) {
                row = cur
                break
            }
            cur = cur.parent
            hops++
        }
        val avatar = findById(row, avatarId) ?: return null
        val ar = Rect().also { avatar.getBoundsInScreen(it) }
        return if (ar.centerX() > screenWidth / 2) Speaker.ME else Speaker.OTHER
    }

    private fun findById(node: AccessibilityNodeInfo?, idName: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.viewIdResourceName?.substringAfterLast('/') == idName) return node
        for (i in 0 until node.childCount) {
            findById(node.getChild(i), idName)?.let { return it }
        }
        return null
    }

    // ---- Generic path ----------------------------------------------------

    private fun collectLeafText(
        node: AccessibilityNodeInfo?,
        out: MutableList<Pair<Rect, String>>,
    ) {
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
        // QQ new-friend banners are not chat content.
        if (text.contains("已成功添加为好友")) return false
        if (text == "以上为验证消息") return false
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
