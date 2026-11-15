package com.octos.aichat.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Coordinates are real device OCR boxes from a WeChat group chat (1224 px wide). */
class SenderNameFilterTest {

    private val differentBg: (OcrBlock, OcrBlock) -> Boolean = { _, _ -> false }
    private val sameBg: (OcrBlock, OcrBlock) -> Boolean = { _, _ -> true }

    private val name1 = OcrBlock(199, 930, 269, 972, "李想")
    // Merged from two single-line blocks (48 px and 52 px tall), as MessageLineMerger yields it.
    private val msg1 = OcrBlock(232, 1020, 985, 1140, "王者荣耀团队看着10个G的APK陷入了沉思", lines = 2, firstLineHeight = 48)
    private val name2 = OcrBlock(197, 1348, 306, 1387, "朱金鹏")
    private val msg2 = OcrBlock(211, 1438, 1003, 1688, "深度使用下来…", lines = 4)
    private val mine = OcrBlock(212, 1923, 1002, 2043, "之前用pus4.8 就挺好,今天升级到5.5看看是不是更好", lines = 2)
    private val name3 = OcrBlock(198, 2251, 305, 2293, "朱金鹏")
    private val msg3 = OcrBlock(232, 2345, 991, 2462, "不同品种…", lines = 2, firstLineHeight = 50)

    @Test fun groupSenderNamesAreDropped() {
        val screen = listOf(name1, msg1, name2, msg2, mine, name3, msg3)
        assertEquals(
            listOf(msg1, msg2, mine, msg3),
            SenderNameFilter.drop(screen, differentBg),
        )
    }

    @Test fun sameBackgroundKeepsIt() {
        // Two consecutive bubbles from one sender: a short message is not a name.
        assertEquals(2, SenderNameFilter.drop(listOf(name1, msg1), sameBg).size)
    }

    @Test fun messageSizedTextIsNotAName() {
        // A short single-line bubble (message-sized type) above the next bubble.
        val shortMsg = OcrBlock(232, 900, 290, 950, "好的")
        assertEquals(2, SenderNameFilter.drop(listOf(shortMsg, msg1), differentBg).size)
    }

    @Test fun shapeRules() {
        // Too far above its bubble.
        assertEquals(false, SenderNameFilter.looksLikeNameAbove(name1, msg1.copy(top = 1100, bottom = 1220)))
        // The block below is far to the right (my bubble, a centred item).
        assertEquals(false, SenderNameFilter.looksLikeNameAbove(name1, msg1.copy(left = 820)))
        // Multi-line or long text is a message, not a name.
        assertEquals(false, SenderNameFilter.looksLikeNameAbove(name1.copy(lines = 2, bottom = 1014), msg1.copy(top = 1060)))
        assertEquals(false, SenderNameFilter.looksLikeNameAbove(name1.copy(text = "x".repeat(21)), msg1))
        // Last block on screen has nothing below it.
        assertEquals(listOf(name1), SenderNameFilter.drop(listOf(name1), differentBg))
    }

    @Test fun backgroundCheckOnlyAskedForNameShapedBlocks() {
        var asked = 0
        SenderNameFilter.drop(listOf(msg1, mine)) { _, _ -> asked++; false }
        assertEquals(0, asked)
    }
}
