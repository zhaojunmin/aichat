package com.octos.aichat.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.octos.aichat.AppBus
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.SettingsStore
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.Tracing

/**
 * Wiring only: receives the system's accessibility callbacks and hands them to the
 * pieces that act on them.
 *
 * - [ForegroundTracker]: which app is in front (shows / hides the bubble), per event.
 * - [ChatWindowReader]: reading the chat on bubble tap (node tree, or screenshot for OCR).
 * - [ReplyFiller]: dropping the chosen reply into the input box.
 * - [RawTreeDumper]: debug-only node tree capture.
 */
class ChatAccessibilityService : AccessibilityService() {

    private val settings: SettingsStore by lazy { SettingsRepository.get(this) }
    private val isTarget: (String) -> Boolean = { it in settings.current().targetPackageSet }

    private val foreground = ForegroundTracker(isTarget, { AppBus.captureRawTree }, AppBus::onForeground)
    private val treeDumper = RawTreeDumper({ rootInActiveWindow }, isTarget)
    private val reader = ChatWindowReader(this)
    private val replyFiller = ReplyFiller { rootInActiveWindow }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppBus.attachAccessibility(reader, replyFiller)
    }

    /**
     * Main thread, on every event: cheap foreground tracking only. The chat itself
     * is parsed on demand ([ChatWindowReader.readConversation]) when the user taps
     * the bubble — no per-event tree walks while they're just chatting.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (foreground.canSkip(event)) return
        Tracing.section(Tracing.A11Y_EVENT) {
            guarded {
                val app = foreground.track(rootInActiveWindow)
                if (app?.isTarget == true) treeDumper.request()
            }
        }
    }

    /**
     * An exception escaping an accessibility callback crashes the process, and the
     * system then disables the service until the user re-enables it by hand. A
     * failed read is harmless (the next event retries), so never let one escape.
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.A11Y_301, cause = e)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        treeDumper.close()
        AppBus.detachAccessibility(reader)
    }
}
