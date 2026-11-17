package com.octos.aichat.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sizes follow device measurements: message lines 48–52 px, secondary text 39–42 px. */
class QuoteFilterTest {

    private fun block(top: Int, firstLine: Int, text: String, lines: Int = 1) =
        OcrBlock(200, top, 1000, top + firstLine * lines, text, lines, firstLine)

    private val theirs = block(400, 50, "明天几点到?")
    private val reply = block(600, 48, "十点吧", lines = 2)
    private val quote = block(720, 41, "张三:明天几点到?")
    private val next = block(900, 52, "好的")

    @Test fun quoteUnderAReplyIsDropped() {
        assertEquals(
            listOf(theirs, reply, next),
            QuoteFilter.drop(listOf(theirs, reply, quote, next)),
        )
    }

    @Test fun imageTextDoesNotHideTheQuote() {
        val image = (0 until 9).map { block(1100 + it * 40, 24, "图里的小字$it") }
        val bottomReply = block(1600, 50, "嗯 所以需要组织导向配套")
        val bottomQuote = block(1730, 41, "华为肖亚军:大模型现在的问题")
        assertEquals(
            listOf(theirs) + image + bottomReply,
            QuoteFilter.drop(listOf(theirs) + image + listOf(bottomReply, bottomQuote)),
        )
    }

    @Test fun smallTextFarBelowIsKept() {
        val far = block(1200, 41, "远处的小字")
        assertEquals(listOf(next, far), QuoteFilter.drop(listOf(next, far)))
    }

    @Test fun aReplyAndItsQuoteAloneStillMeasureTheReply() {
        assertEquals(listOf(reply), QuoteFilter.drop(listOf(reply, quote)))
    }

    @Test fun messageSizeJitterIsKept() {
        val screen = listOf(theirs, reply, next, block(1000, 48, "嗯"))
        assertEquals(screen, QuoteFilter.drop(screen))
    }

    @Test fun aSingleBlockIsKept() {
        assertEquals(listOf(quote), QuoteFilter.drop(listOf(quote)))
    }
}
