package com.octos.aichat.ocr

/**
 * Which OCR'd text blocks are chat messages rather than screen chrome (timestamps,
 * system notices, unread badges, input bar). Android-free so it's JVM-testable.
 */
internal object OcrMessageFilter {

    val DATE_RE = Regex("""\d{4}-\d{1,2}-\d{1,2}""")

    // WeChat input-bar chrome that OCR may pick up but isn't chat content.
    private val UI_EXACT = setOf("发送", "请输入消息")

    private val SHORT_TIME = Regex("""^(上午|下午|昨天|今天)?\s*\d{1,2}:\d{2}$""")

    // A time with a few stray glyphs in front ("BF 20:16"): OCR noise from the
    // wallpaper beside a centred timestamp. Only non-CJK leftovers count, so a
    // real message like "明天 10:30" is kept.
    private val NOISY_TIME = Regex("""^[^一-鿿]{0,4}\s+\d{1,2}:\d{2}$""")

    private val BADGE = Regex("""^\d{1,3}$""")

    fun looksLikeMessage(text: String): Boolean {
        if (text.length > 500) return false
        if (text.contains("正在输入")) return false
        if (text.contains("撤回了一条消息")) return false
        if (text.endsWith("加入了群聊") || text.endsWith("加入群聊")) return false
        // Date-time separators (e.g. "2026-11-10 09:15:31").
        if (DATE_RE.containsMatchIn(text)) return false
        // Short time stamps like "12:34" / "下午 3:51", with or without OCR noise.
        if (text.matches(SHORT_TIME) || text.matches(NOISY_TIME)) return false
        // Bare digit badges (unread counts like "4", "100").
        if (text.matches(BADGE)) return false
        // Input-bar chrome.
        if (text in UI_EXACT) return false
        return true
    }
}
