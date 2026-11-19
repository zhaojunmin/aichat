package com.octos.aichat.ocr

/**
 * When someone replies with a quote, WeChat shows the quoted text in a small box right
 * under the reply bubble, and whole-screen OCR reads it as one more message. Quotes are
 * set in a smaller, grey font, so this drops every block that sits right under another
 * block (within [MAX_GAP_LINES] of its line height) in a clearly smaller type size or a
 * much fainter colour ([OcrBlock.isFaintComparedTo]). Colour matters because size alone
 * misses quotes with an emoji or Latin descenders (g, _), whose line box grows to the
 * height of a message line.
 *
 * The reference is the block directly above, not a screen-wide usual size: an image
 * in the chat (a screenshot of a paper, say) adds dozens of tiny text blocks that pull
 * any screen-wide median down to their size, after which no quote looks small.
 * Android-free so it's JVM-testable.
 */
internal object QuoteFilter {

    // Measured on a real device: secondary text 39–42 px tall, message lines 48–52 px.
    private const val MAX_SIZE_RATIO = 0.9f

    // Reply text to quote text measured ~80 px with 50 px lines.
    private const val MAX_GAP_LINES = 2

    /** @param blocks top to bottom. */
    fun drop(blocks: List<OcrBlock>): List<OcrBlock> =
        blocks.filterIndexed { i, b -> i == 0 || !isQuoteUnder(blocks[i - 1], b) }

    private fun isQuoteUnder(above: OcrBlock, b: OcrBlock): Boolean =
        b.top - above.bottom <= above.firstLineHeight * MAX_GAP_LINES &&
            (
                b.firstLineHeight < above.firstLineHeight * MAX_SIZE_RATIO ||
                    b.isFaintComparedTo(above)
                )
}
