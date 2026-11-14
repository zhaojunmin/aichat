package com.octos.aichat.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.Speaker

/**
 * Turns the on-screen accessibility node tree of a chat app into a [Conversation].
 *
 * Two strategies:
 *  1. **Precise (per-app rule)** — when we know an app's message-bubble id, we
 *     collect only those text nodes (excluding profile cards, suggested openers,
 *     menus…). The author is read from the row's avatar position, which is
 *     pinned to the left (other) or right (me) regardless of message length —
 *     far more reliable than the text's horizontal center, which fails for long
 *     bubbles that span most of the width. Verified for Soul.
 *  2. **Generic (fallback)** — for unknown apps, collect leaf text nodes in the
 *     vertical middle band and guess the author from the bubble's position.
 */
object ChatParser {

    /**
     * Per-app parsing rule. Two shapes, matched by view-id *name* (the part after
     * ':id/', so a differing resource package doesn't matter):
     *  - [selfId]/[othersId]: the message text node's own id tells the author
     *    (e.g. Hertz: tv_chat_content_self / tv_chat_content_others).
     *  - [messageId]+[avatarId]: a single message id; the author is read from the
     *    row's avatar position (e.g. Soul).
     */
    private data class AppRule(
        val selfId: String? = null,
        val othersId: String? = null,
        val messageId: String? = null,
        val avatarId: String? = null,
    )

    private val APP_RULES: Map<String, AppRule> = mapOf(
        "cn.soulapp.android" to AppRule(
            messageId = "content_text",
            avatarId = "chat_avatar",
        ),
        // Hertz: the text node's id distinguishes speaker directly.
        "com.immomo.vchat" to AppRule(
            selfId = "tv_chat_content_self",
            othersId = "tv_chat_content_others",
        ),
        // QQ. All chat-bubble text nodes share one view-id; profile-card and
        // header texts use different ids, so this id alone selects messages. The
        // id is R8-obfuscated, so it changes between QQ versions — if QQ updates
        // and this stops matching, parse() falls back to the generic path rather
        // than returning nothing. No stable avatar id, so the author is read from
        // bubble position (me = right, other = left), which QQ lays out reliably.
        "com.tencent.mobileqq" to AppRule(
            messageId = "mjn",
        ),
    )

    private data class Raw(val bounds: Bounds, val text: String, val speaker: Speaker?)

    // Text we never want to treat as a chat message (generic path only).
    private val NOISE = setOf(
        "发送", "语音", "表情", "更多", "按住 说话", "按住说话", "返回",
        "拍摄", "相册", "视频通话", "语音通话", "输入", "聊天信息", "转账",
    )

    /** Live-tree entry point: snapshot once, then parse the plain [UiNode] tree. */
    fun parse(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        pkg: String,
    ): Conversation = parse(root?.toUiNode(), screenWidth, screenHeight, pkg)

    fun parse(
        root: UiNode?,
        screenWidth: Int,
        screenHeight: Int,
        pkg: String,
    ): Conversation {
        if (root == null || screenWidth <= 0) return Conversation(pkg, emptyList())

        val rule = APP_RULES[pkg]
        val raws = ArrayList<Raw>()

        if (rule != null) {
            collectByRule(root, ArrayDeque(), rule, screenWidth, raws)
        }
        // Generic path — used when the app has no rule, or when its rule matched
        // nothing (e.g. QQ updated and its obfuscated view-id changed). Degrading
        // to position-based parsing beats returning an empty conversation.
        if (raws.isEmpty()) {
            val leaves = ArrayList<Pair<Bounds, String>>()
            collectLeafText(root, leaves)
            applyVerticalBand(leaves, screenHeight)
            leaves.mapTo(raws) { Raw(it.first, it.second, null) }
        }

        // Top-to-bottom, then left-to-right = chronological for a chat list.
        val ordered = raws.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))

        val messages = ArrayList<ChatMessage>()
        var lastText: String? = null
        for (raw in ordered) {
            if (!looksLikeMessage(raw.text)) continue
            if (raw.text == lastText) continue // collapse duplicates from re-layout
            val speaker = raw.speaker ?: classify(raw.bounds, screenWidth)
            messages.add(ChatMessage(speaker, raw.text))
            lastText = raw.text
        }
        return Conversation(pkg, messages)
    }

    // ---- Precise path ----------------------------------------------------

    /** [ancestors] = the path from the root down to [node]'s parent (nearest last). */
    private fun collectByRule(
        node: UiNode,
        ancestors: ArrayDeque<UiNode>,
        rule: AppRule,
        screenWidth: Int,
        out: MutableList<Raw>,
    ) {
        val id = node.idName
        val isMessage = id != null &&
            (id == rule.selfId || id == rule.othersId || id == rule.messageId)
        if (isMessage) {
            val text = node.label
            if (text != null && !node.bounds.isEmpty) {
                val speaker: Speaker? = when (id) {
                    rule.selfId -> Speaker.ME
                    rule.othersId -> Speaker.OTHER
                    else -> rule.avatarId?.let { senderFromAvatar(node, ancestors, it, screenWidth) }
                }
                out.add(Raw(node.bounds, text, speaker))
            }
        }
        ancestors.addLast(node)
        for (child in node.children) collectByRule(child, ancestors, rule, screenWidth, out)
        ancestors.removeLast()
    }

    /**
     * Walk up to the message row, then find the avatar inside it; the avatar's
     * horizontal center tells us the author. Returns null if no avatar is found
     * (the caller then falls back to position-based classification).
     */
    private fun senderFromAvatar(
        messageNode: UiNode,
        ancestors: ArrayDeque<UiNode>,
        avatarId: String,
        screenWidth: Int,
    ): Speaker? {
        // The row is the nearest ancestor (within 8 hops) spanning most of the width.
        val row = ancestors.asReversed().take(8)
            .firstOrNull { it.bounds.width > screenWidth * 0.85f } ?: messageNode
        val avatar = findById(row, avatarId) ?: return null
        return if (avatar.bounds.centerX > screenWidth / 2) Speaker.ME else Speaker.OTHER
    }

    private fun findById(node: UiNode, idName: String): UiNode? {
        if (node.idName == idName) return node
        for (child in node.children) findById(child, idName)?.let { return it }
        return null
    }

    // ---- Generic path ----------------------------------------------------

    private fun collectLeafText(node: UiNode, out: MutableList<Pair<Bounds, String>>) {
        if (node.children.isEmpty()) {
            val text = node.label
            if (text != null && !node.bounds.isEmpty) out.add(node.bounds to text)
        }
        for (child in node.children) collectLeafText(child, out)
    }

    /** Keep only the scrollable message band, dropping title bar and input bar. */
    private fun applyVerticalBand(items: MutableList<Pair<Bounds, String>>, screenHeight: Int) {
        if (screenHeight <= 0) return
        val top = screenHeight * 0.09f
        val bottom = screenHeight * 0.90f
        items.retainAll { it.first.centerY.toFloat() in top..bottom }
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

    private fun classify(b: Bounds, screenWidth: Int): Speaker {
        val leftZone = screenWidth * 0.18f
        val rightZone = screenWidth * 0.82f
        return when {
            b.right >= rightZone && b.left > leftZone -> Speaker.ME
            b.left <= leftZone && b.right < rightZone -> Speaker.OTHER
            // Ambiguous (full-width): fall back to which half the center sits in.
            b.centerX >= screenWidth / 2 -> Speaker.ME
            else -> Speaker.OTHER
        }
    }
}
