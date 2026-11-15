package com.octos.aichat

import android.annotation.SuppressLint
import com.octos.aichat.accessibility.ChatWindowReader
import com.octos.aichat.accessibility.ForegroundApp
import com.octos.aichat.accessibility.ReplyFiller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny in-process bus that lets the overlay and the UI reach the accessibility
 * reader without a DI framework. Service references are main-thread only.
 */
object AppBus {

    private val _foreground = MutableStateFlow<ForegroundApp?>(null)

    /** The app in front, as last seen by the accessibility reader; null until known. */
    val foreground: StateFlow<ForegroundApp?> = _foreground

    val foregroundPackage: String? get() = _foreground.value?.pkg

    /**
     * Set while the accessibility service is connected. Both wrap the service, so
     * they're cleared in [detachAccessibility] from its onDestroy — no leak.
     */
    @SuppressLint("StaticFieldLeak")
    var chatWindowReader: ChatWindowReader? = null
        private set
    var replyFiller: ReplyFiller? = null
        private set

    fun attachAccessibility(reader: ChatWindowReader, filler: ReplyFiller) {
        chatWindowReader = reader
        replyFiller = filler
    }

    fun detachAccessibility(reader: ChatWindowReader) {
        if (chatWindowReader !== reader) return
        chatWindowReader = null
        replyFiller = null
    }

    /** Called by the reader on each foreground check; observers see only changes. */
    fun onForeground(app: ForegroundApp) {
        _foreground.value = app
    }

    val isAccessibilityConnected: Boolean get() = chatWindowReader != null
}
