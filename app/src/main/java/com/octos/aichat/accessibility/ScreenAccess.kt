package com.octos.aichat.accessibility

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import androidx.annotation.RequiresApi
import com.octos.aichat.data.Conversation

/**
 * What the rest of the app may do with the screen while the accessibility service is
 * connected: read the chat in front, get the pieces for the OCR path, and fill a reply.
 * One instance per connection, published through [AccessibilityBridge.screen].
 */
interface ScreenAccess {

    /** Parse [pkg]'s chat window as it is right now; empty when it can't be read. */
    suspend fun readConversation(pkg: String): Conversation

    /** Message bounds for apps with a [MessageBoundsLocator] rule; empty otherwise. */
    suspend fun locateMessageBounds(pkg: String): List<MessageBounds>

    /** The current screen as a software bitmap ready for OCR, or null on failure. */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenshot(pkg: String): Bitmap?

    /** Screen bounds of the soft keyboard if it is showing, else null. */
    fun imeBounds(): Rect?

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

    override suspend fun locateMessageBounds(pkg: String) = reader.locateMessageBounds(pkg)

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun captureScreenshot(pkg: String) = reader.captureScreenshot(pkg)

    override fun imeBounds() = reader.imeBounds()

    override suspend fun fill(text: String, pkg: String?) = filler.fill(text, pkg)
}
