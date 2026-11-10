package com.octos.aichat

import com.octos.aichat.accessibility.ChatAccessibilityService
import com.octos.aichat.data.Conversation
import com.octos.aichat.overlay.FloatingBubbleService

/**
 * Tiny in-process bus that lets the accessibility reader, the overlay and the
 * UI find each other without a DI framework. All access is on the main thread
 * except [conversation], which is written from accessibility callbacks and read
 * from the overlay — hence @Volatile.
 */
object AppBus {

    @Volatile
    var conversation: Conversation = Conversation.empty()
        private set

    @Volatile
    var foregroundPackage: String? = null
        private set

    /** Debug: the last raw node tree captured from a target app (id/class/bounds/text). */
    @Volatile
    var lastRawDump: String = "(尚未读取。请先在聊天界面停留片刻)"

    var accessibilityService: ChatAccessibilityService? = null
    var bubbleService: FloatingBubbleService? = null

    fun updateConversation(conv: Conversation) {
        conversation = conv
    }

    /** Called by the reader as the foreground app changes; toggles the bubble. */
    fun onForeground(pkg: String?, isTarget: Boolean) {
        foregroundPackage = pkg
        bubbleService?.setBubbleVisible(isTarget)
    }

    val isAccessibilityConnected: Boolean get() = accessibilityService != null
    val isOverlayRunning: Boolean get() = bubbleService != null
}
