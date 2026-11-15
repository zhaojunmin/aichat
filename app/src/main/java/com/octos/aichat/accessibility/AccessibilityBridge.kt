package com.octos.aichat.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the overlay and the UI reach the accessibility service. The system binds that
 * service itself (its `onBind` is final), so other components can't bind to it; it
 * publishes its [ScreenAccess] here instead, in-process, for as long as it is connected.
 */
object AccessibilityBridge {

    private val _screen = MutableStateFlow<ScreenAccess?>(null)

    /** The connected screen; null while the accessibility service is off. */
    val screen: StateFlow<ScreenAccess?> = _screen.asStateFlow()

    val isConnected: Boolean get() = _screen.value != null

    private val _foreground = MutableStateFlow<ForegroundApp?>(null)

    /** The app in front, as last seen by the service; null until known. Observers see only changes. */
    val foreground: StateFlow<ForegroundApp?> = _foreground.asStateFlow()

    val foregroundPackage: String? get() = _foreground.value?.pkg

    internal fun connect(screen: ScreenAccess) {
        _screen.value = screen
    }

    /** Only clears [screen] if it is still this connection's (a reconnect may have replaced it). */
    internal fun disconnect(screen: ScreenAccess) {
        _screen.compareAndSet(screen, null)
    }

    internal fun onForeground(app: ForegroundApp) {
        _foreground.value = app
    }
}
