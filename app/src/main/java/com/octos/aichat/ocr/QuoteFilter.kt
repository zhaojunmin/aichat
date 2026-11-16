package com.octos.aichat.ocr

/**
 * When the user replies with a quote, WeChat shows the quoted text in a small box right
 * under the reply bubble, and whole-screen OCR reads it as one more message — on the
 * right, so as one of the user's own. Quotes (like other secondary text: card sizes,
 * link descriptions) are set in a smaller font than message text, so this drops every
 * block whose type size is clearly below the screen's usual message size.
 *
 * The usual size is the upper median of the blocks' first-line heights, so a screen with
 * a single message next to its quote still measures the message. Fewer than two blocks:
 * nothing to compare, everything is kept. Android-free so it's JVM-testable.
 */
internal object QuoteFilter {

    // Measured on a real device: secondary text 39–42 px tall, message lines 48–52 px.
    private const val MAX_SIZE_RATIO = 0.9f

    fun drop(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size < 2) return blocks
        val usual = blocks.map { it.firstLineHeight }.sorted()[blocks.size / 2]
        return blocks.filterNot { it.firstLineHeight < usual * MAX_SIZE_RATIO }
    }
}
