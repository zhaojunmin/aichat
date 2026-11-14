package com.octos.aichat.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrMessageFilterTest {

    private fun msg(t: String) = OcrMessageFilter.looksLikeMessage(t)

    @Test fun chromeIsRejected() {
        listOf(
            "12:34", "下午 3:51", "昨天 21:16", "2026-11-10 09:15:31",
            "星期三 21:16", "周日 08:05", "11月5日 12:53", "11月5日13:26", "2025年11月5日 下午3:51",
            "4", "100", "发送", "请输入消息", "对方正在输入...", "\"小明\" 撤回了一条消息", "小红加入了群聊",
            "x".repeat(501),
        ).forEach { assertFalse(it, msg(it)) }
    }

    /** Seen on a device: wallpaper glyphs OCR'd onto a centred timestamp. */
    @Test fun timestampWithStrayGlyphsIsRejected() {
        listOf("BF 20:16", "BFÆ 10:30", "| 9:05").forEach { assertFalse(it, msg(it)) }
    }

    @Test fun realMessagesAreKept() {
        listOf(
            "明天 10:30", "好的", "约在 10:30 吧", "Meet at 10:30", "ok", "2000", "5.5 看看是不是更好",
        ).forEach { assertTrue(it, msg(it)) }
    }
}
