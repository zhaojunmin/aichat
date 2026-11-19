package com.octos.aichat.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageLineMergerTest {

    // Layout from a 1224-px-wide WeChat screen: ~45 px text lines, ~12 px line
    // spacing inside a bubble, ≥ 80 px between bubbles.
    private fun line(top: Int, text: String, left: Int = 230, right: Int = 1000) =
        OcrBlock(left, top, right, top + 45, text)

    private val always: (OcrBlock, OcrBlock) -> Boolean = { _, _ -> true }

    @Test fun wrappedLinesOfOneMessageAreMerged() {
        val out = MessageLineMerger.merge(
            listOf(line(2300, "不同品种,5.5远超4.8,5.5各种"), line(2357, "场景都非常稳,又快又稳又好", right = 900)),
            always,
        )
        assertEquals(listOf("不同品种,5.5远超4.8,5.5各种场景都非常稳,又快又稳又好"), out.map { it.text })
        assertEquals(
            OcrBlock(230, 2300, 1000, 2402, out[0].text, lines = 2, firstLineHeight = 45),
            out[0],
        )
    }

    /** Real device boxes: lines of one bubble started at x=212 and x=231 (OCR jitter). */
    @Test fun leftEdgeJitterWithinALineHeightStillMerges() {
        val out = MessageLineMerger.merge(
            listOf(
                OcrBlock(212, 1923, 1002, 1984, "之前用pus4.8 就挺好,今天升级到"),
                OcrBlock(231, 1995, 662, 2043, "5.5看看是不是更好"),
            ),
            always,
        )
        assertEquals(listOf("之前用pus4.8 就挺好,今天升级到5.5看看是不是更好"), out.map { it.text })
    }

    /** Real device boxes: a group sender's name 52 px above its bubble's first line. */
    @Test fun senderNameAboveMessageIsNotMerged() {
        val name = OcrBlock(198, 2251, 305, 2293, "朱金鹏")
        val first = OcrBlock(232, 2345, 991, 2395, "不同品种")
        assertEquals(2, MessageLineMerger.merge(listOf(name, first), always).size)
    }

    @Test fun threeLinesBecomeOne_inputOrderDoesNotMatter() {
        val out = MessageLineMerger.merge(
            listOf(line(1114, "三"), line(1000, "一"), line(1057, "二")),
            always,
        )
        assertEquals(listOf("一二三"), out.map { it.text })
    }

    @Test fun separateMessagesStayApart() {
        // Same sender, same left edge, but the usual inter-bubble gap.
        val out = MessageLineMerger.merge(listOf(line(1000, "第一条"), line(1130, "第二条")), always)
        assertEquals(listOf("第一条", "第二条"), out.map { it.text })
    }

    @Test fun pixelCheckVetoesTightMessages() {
        val out = MessageLineMerger.merge(listOf(line(1000, "第一条"), line(1057, "第二条"))) { _, _ ->
            false
        }
        assertEquals(2, out.size)
    }

    @Test fun differentLeftEdgeIsNotTheSameMessage() {
        // A centred timestamp right under a message, and the other party's bubble vs mine.
        val stamp = line(1057, "21:16", left = 560, right = 660)
        val mine = line(1057, "好的", left = 820, right = 990)
        assertEquals(2, MessageLineMerger.merge(listOf(line(1000, "消息"), stamp), always).size)
        assertEquals(2, MessageLineMerger.merge(listOf(line(1000, "消息"), mine), always).size)
    }

    @Test fun multiLineBlockUsesPerLineHeight() {
        // A 2-line block (90 px tall) followed by its third line: line height is 45, not 90.
        val twoLines = OcrBlock(230, 1000, 1000, 1090, "一二", lines = 2)
        assertEquals(1, MessageLineMerger.merge(listOf(twoLines, line(1102, "三")), always).size)
        // ...and a gap that only fits a 90-px "line" must not merge.
        assertEquals(2, MessageLineMerger.merge(listOf(twoLines, line(1170, "三")), always).size)
    }

    @Test fun pixelCheckOnlyAskedForGeometricNeighbours() {
        var asked = 0
        MessageLineMerger.merge(listOf(line(1000, "a"), line(1300, "b"))) { _, _ ->
            asked++
            true
        }
        assertEquals(0, asked)
    }

    @Test fun joinLines_cjkGluedLatinSpaced() {
        assertEquals("各种场景", MessageLineMerger.joinLines("各种", "场景"))
        assertEquals("5.5各种场景", MessageLineMerger.joinLines("5.5各种", "场景"))
        assertEquals("用了Opus", MessageLineMerger.joinLines("用了", "Opus"))
        assertEquals("好的，明天见", MessageLineMerger.joinLines("好的，", "明天见"))
        assertEquals("hello, world", MessageLineMerger.joinLines("hello,", "world"))
        assertEquals("Opus 5.5", MessageLineMerger.joinLines("Opus ", " 5.5"))
        assertEquals("x", MessageLineMerger.joinLines("", "x"))
    }

    /**
     * A grey quote right under a reply: its pale box can pass the background check
     * (white bubble vs #E8EFF3 box is within tolerance), but its text is much fainter.
     */
    @Test fun aFaintQuoteIsNotGluedOntoTheReply() {
        val reply = line(2300, "我又卡出来了").copy(contrast = 17.5f)
        val quote = line(2357, "朱金鹏:我的也掉了").copy(contrast = 4.5f)
        val out = MessageLineMerger.merge(listOf(reply, quote), always)
        assertEquals(listOf("我又卡出来了", "朱金鹏:我的也掉了"), out.map { it.text })
    }

    @Test fun linesInTheSameColourStillMerge() {
        val out = MessageLineMerger.merge(
            listOf(
                line(2300, "我这还没掉。。别封号就行。").copy(contrast = 17.4f),
                line(2357, "_(:3」∠)_").copy(contrast = 17.4f),
            ),
            always,
        )
        assertEquals(1, out.size)
        assertEquals(17.4f, out[0].contrast)
    }
}
