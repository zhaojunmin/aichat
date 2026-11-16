package com.octos.aichat.accessibility

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import com.octos.aichat.data.Conversation

/**
 * What the OCR path recognises: the screen as a software bitmap — cut off above the soft
 * keyboard when it is up and the app gives no [messageBounds] — and the message bounds
 * of apps with a [MessageBoundsLocator] rule, taken at the same moment as the pixels.
 * The caller owns [bitmap] and recycles it.
 */
class ChatScreenshot(val bitmap: Bitmap, val messageBounds: List<MessageBounds>)

/**
 * What the rest of the app may do with the screen while the accessibility service is
 * connected: read the chat in front, capture it for OCR, and fill a reply.
 * One instance per connection, published through [AccessibilityBridge.screen].
 */
interface ScreenAccess {

    /** Parse [pkg]'s chat window as it is right now; empty when it can't be read. */
    suspend fun readConversation(pkg: String): Conversation

    /** [pkg]'s chat as it is right now, ready for OCR; null when the screenshot failed. */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenshot(pkg: String): ChatScreenshot?

    /**
     * Put [text] into the chat's input box.
     * @return true if a field was filled; on false, `A11Y-305` is already recorded.
     */
    suspend fun fill(text: String, pkg: String?): Boolean
}

/** The live [ScreenAccess]: reading via [reader], filling via [filler]. */
internal class AccessibilityScreen(
    private val reader: ChatWindowReader,
    private val filler: ReplyFiller,
) : ScreenAccess {
    override suspend fun readConversation(pkg: String) = reader.readConversation(pkg)

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun captureScreenshot(pkg: String) = reader.captureScreenshot(pkg)

    override suspend fun fill(text: String, pkg: String?) = filler.fill(text, pkg)
}
