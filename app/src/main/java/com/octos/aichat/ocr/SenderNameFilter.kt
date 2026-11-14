package com.octos.aichat.ocr

/**
 * In group chats WeChat prints the sender's name above each of their bubbles, and
 * whole-screen OCR reads it as a message from the other party. This drops a block
 * when it looks like such a name, i.e. ALL of:
 *
 * - shape: a single short line, with a block starting right below it (gap within
 *   two line heights) whose left edge is at or a little right of the name's —
 *   the bubble's text is indented by its padding;
 * - type size: names use a smaller font than message text;
 * - background ([sameBackground] false): the name sits on the chat background /
 *   wallpaper while the line below sits inside a bubble.
 *
 * Any doubt keeps the block, so a miss only leaves a name in, as before.
 * Run after [BubbleLineMerger], so "the block below" is a whole bubble.
 * Android-free so it's JVM-testable.
 */
internal object SenderNameFilter {

    private const val MAX_NAME_CHARS = 20

    // Measured on a real device: names 39–42 px tall, single message lines 48–52 px.
    private const val MAX_SIZE_RATIO = 0.92f

    /** @param blocks top to bottom (as [BubbleLineMerger.merge] returns them). */
    fun drop(blocks: List<OcrBlock>, sameBackground: (name: OcrBlock, below: OcrBlock) -> Boolean): List<OcrBlock> =
        blocks.filterIndexed { i, b ->
            val below = blocks.getOrNull(i + 1)
            !(below != null && looksLikeNameAbove(b, below) && !sameBackground(b, below))
        }

    internal fun looksLikeNameAbove(name: OcrBlock, below: OcrBlock): Boolean {
        if (name.lines != 1 || name.text.length > MAX_NAME_CHARS) return false
        val line = name.lineHeight
        if (line <= 0) return false
        val gap = below.top - name.bottom
        val indent = below.left - name.left
        return gap in 0..2 * line &&
            indent in -line / 2..2 * line &&
            line <= below.firstLineHeight * MAX_SIZE_RATIO
    }
}
