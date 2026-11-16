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

    @Test fun aReplyAndItsQuoteAloneStillMeasureTheReply() {
        assertEquals(listOf(reply), QuoteFilter.drop(listOf(reply, quote)))
    }

    @Test fun messageSizeJitterIsKept() {
        val screen = listOf(theirs, reply, next, block(1000, 46, "嗯"))
        assertEquals(screen, QuoteFilter.drop(screen))
    }

    @Test fun aSingleBlockIsKept() {
        assertEquals(listOf(quote), QuoteFilter.drop(listOf(quote)))
    }
}
