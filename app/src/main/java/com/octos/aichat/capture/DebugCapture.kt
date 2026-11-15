package com.octos.aichat.capture

import com.octos.aichat.data.Conversation

/**
 * Process-wide state behind the developer tools in advanced settings: what the last
 * bubble tap read, and the raw node-tree dump used to write per-app parse rules.
 *
 * Never persisted — it holds raw chat text, so it lives only as long as the process.
 * Written from the overlay and the dump thread, read from the UI, hence @Volatile.
 */
object DebugCapture {

    /** The conversation the last bubble tap read (the in-app viewer shows it). */
    @Volatile
    var lastConversation: Conversation = Conversation.empty()
        private set

    fun recordConversation(conv: Conversation) {
        lastConversation = conv
    }

    /**
     * Capture the raw node tree of target apps into [lastRawDump]. Off by default and
     * deliberately not persisted: walking the whole tree costs time and the dump holds
     * raw chat text, so it only runs while someone is actively adapting a new app.
     * Turning it off drops the last dump.
     */
    @Volatile
    var captureRawTree: Boolean = false
        set(value) {
            field = value
            if (!value) lastRawDump = null
        }

    /** The last raw node tree captured from a target app (id/class/bounds/text); null if none. */
    @Volatile
    var lastRawDump: String? = null
}
