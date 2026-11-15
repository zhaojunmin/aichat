package com.octos.aichat.data

/**
 * How a supported app's chat is read off the screen. Every app takes exactly one way;
 * that choice is the single fact the reader, the OCR path and the settings defaults
 * all derive from.
 */
sealed interface ChatReading {

    /**
     * Read the text straight from the accessibility node tree, matching message nodes by
     * view-id *name* (the part after `:id/`, so a differing resource package doesn't
     * matter). Two shapes:
     *  - [selfId] / [othersId]: the text node's own id tells the author (Hertz);
     *  - [messageId] (+ [avatarId]): one id for every message; the author comes from the
     *    row's avatar position when there is one (Soul), else from where the text sits (QQ).
     */
    data class NodeTree(
        val selfId: String? = null,
        val othersId: String? = null,
        val messageId: String? = null,
        val avatarId: String? = null,
    ) : ChatReading

    /**
     * Screenshot + on-device OCR, for apps whose tree hides the text or is too noisy.
     * [messageBoundsId] names a node that still carries each message's bounds (Momo):
     * OCR is then confined to those rectangles. Null means a whole-screen read (WeChat).
     */
    data class Ocr(val messageBoundsId: String? = null) : ChatReading
}

/** A chat app supported out of the box, and how its messages are read. */
data class SupportedApp(val pkg: String, val name: String, val reading: ChatReading)

/**
 * The one list of built-in chat apps. Adding an app — or changing how one is read —
 * happens here and nowhere else; the parser, the OCR path and the default settings
 * follow.
 */
object SupportedApps {

    const val WECHAT = "com.tencent.mm"
    const val QQ = "com.tencent.mobileqq"
    const val SOUL = "cn.soulapp.android"
    const val HERTZ = "com.immomo.vchat"
    const val MOMO = "com.immomo.momo"

    val ALL: List<SupportedApp> = listOf(
        // Node tree blocked: whole-screen OCR with colour / layout heuristics.
        SupportedApp(WECHAT, "微信", ChatReading.Ocr()),
        // All bubble texts share one id; profile-card and header texts use others. The id
        // is R8-obfuscated and changes between QQ versions — when it stops matching, the
        // parser falls back to its generic path rather than returning nothing. No stable
        // avatar id, so the author comes from bubble position, which QQ lays out reliably.
        SupportedApp(QQ, "QQ", ChatReading.NodeTree(messageId = "mjn")),
        SupportedApp(
            SOUL,
            "Soul",
            ChatReading.NodeTree(messageId = "content_text", avatarId = "chat_avatar"),
        ),
        SupportedApp(
            HERTZ,
            "Hertz",
            ChatReading.NodeTree(
                selfId = "tv_chat_content_self",
                othersId = "tv_chat_content_others",
            ),
        ),
        // Text drawn in a custom view the tree can't read, but the node keeps its bounds.
        SupportedApp(MOMO, "陌陌", ChatReading.Ocr(messageBoundsId = "message_tv_layouttextview")),
    )

    private val byPackage: Map<String, SupportedApp> = ALL.associateBy { it.pkg }

    fun of(pkg: String): SupportedApp? = byPackage[pkg]

    /** Built-in apps the assistant offers suggestions inside. */
    val PACKAGES: Set<String> = byPackage.keys

    /** Built-in apps read via screenshot + OCR. */
    val OCR_PACKAGES: Set<String> = ALL.filter {
        it.reading is ChatReading.Ocr
    }.map { it.pkg }.toSet()

    /** The node-tree rule for [pkg], if it is a built-in node-tree app. */
    fun nodeTreeRule(pkg: String): ChatReading.NodeTree? = of(pkg)?.reading as? ChatReading.NodeTree

    /** The bounds-carrying node id for [pkg], if it is an OCR app that exposes one. */
    fun messageBoundsId(pkg: String): String? =
        (of(pkg)?.reading as? ChatReading.Ocr)?.messageBoundsId
}
