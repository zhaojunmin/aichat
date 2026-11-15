package com.octos.aichat

import android.annotation.SuppressLint
import com.octos.aichat.accessibility.ChatWindowReader
import com.octos.aichat.accessibility.ForegroundApp
import com.octos.aichat.accessibility.ReplyFiller
import com.octos.aichat.bubble.FloatingBubbleService
import com.octos.aichat.data.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny in-process bus that lets the accessibility reader, the overlay and the
 * UI find each other without a DI framework. Service references are main-thread
 * only; the debug fields are touched from the capture thread too — hence @Volatile.
 */
object AppBus {

    /** Debug: the conversation the last bubble tap read (the in-app viewer shows it). */
    @Volatile
    var conversation: Conversation = Conversation.empty()
        private set

    private val _foreground = MutableStateFlow<ForegroundApp?>(null)

    /** The app in front, as last seen by the accessibility reader; null until known. */
    val foreground: StateFlow<ForegroundApp?> = _foreground

    val foregroundPackage: String? get() = _foreground.value?.pkg

    /**
     * Debug: capture the raw node tree of target apps into [lastRawDump]. Off by
     * default and deliberately not persisted — walking the whole tree costs time
     * and the dump holds raw chat text, so it only runs while someone is actively
     * adapting a new app, and resets when the process dies.
     */
    @Volatile
    var captureRawTree: Boolean = false
        set(value) {
            field = value
            if (!value) lastRawDump = null
        }

    /**
     * Debug: the last raw node tree captured from a target app (id/class/bounds/text); null if
     * none.
     */
    @Volatile
    var lastRawDump: String? = null

    /**
     * Set while the accessibility service is connected. Both wrap the service, so
     * they're cleared in [detachAccessibility] from its onDestroy — no leak.
     */
    @SuppressLint("StaticFieldLeak")
    var chatWindowReader: ChatWindowReader? = null
        private set
    var replyFiller: ReplyFiller? = null
        private set

    // Cleared in FloatingBubbleService.onDestroy, so the service never outlives its lifecycle here.
    @SuppressLint("StaticFieldLeak")
    var bubbleService: FloatingBubbleService? = null

    fun attachAccessibility(reader: ChatWindowReader, filler: ReplyFiller) {
        chatWindowReader = reader
        replyFiller = filler
    }

    fun detachAccessibility(reader: ChatWindowReader) {
        if (chatWindowReader !== reader) return
        chatWindowReader = null
        replyFiller = null
    }

    fun updateConversation(conv: Conversation) {
        conversation = conv
    }

    /** Called by the reader on each foreground check; observers see only changes. */
    fun onForeground(app: ForegroundApp) {
        _foreground.value = app
    }

    val isAccessibilityConnected: Boolean get() = chatWindowReader != null
    val isOverlayRunning: Boolean get() = bubbleService != null
}
