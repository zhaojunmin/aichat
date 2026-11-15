package com.octos.aichat.accessibility

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import com.octos.aichat.data.Conversation

/**
 * What the overlay needs from the screen on a bubble tap: the chat as parsed from
 * the node tree, or the pieces for the OCR path (bubble geometry, a screenshot,
 * the keyboard area to crop away). Implemented by [ChatAccessibilityService].
 */
interface ChatWindowReader {

    /** Parse [pkg]'s chat window as it is right now; empty when it can't be read. */
    suspend fun readConversation(pkg: String): Conversation

    /** Bubble rectangles for apps with a [BubbleLocator] rule; empty otherwise. */
    suspend fun locateBubbles(pkg: String): List<BubbleBox>

    /** A software bitmap of the current screen, or null (secure window, rate limit…). */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenshot(pkg: String): Bitmap?

    /** Screen bounds of the soft keyboard if it is showing, else null. */
    fun imeBounds(): Rect?
}
