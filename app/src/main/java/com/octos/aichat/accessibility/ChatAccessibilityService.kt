package com.octos.aichat.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import com.octos.aichat.capture.DebugCapture
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.SettingsStore
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.Tracing
import java.util.concurrent.atomic.AtomicBoolean

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

    private val foreground =
        ForegroundTracker(isTarget, {
            DebugCapture.captureRawTree
        }, AccessibilityBridge::onForeground)
    private val treeDumper = RawTreeDumper({ rootInActiveWindow }, isTarget)
    private val reader = ChatWindowReader(this)
    private val screen = AccessibilityScreen(reader, ReplyFiller { rootInActiveWindow })

    // The active-window lookup is a binder call served by the chat app's UI thread; while
    // that app starts it can take over a second (measured: 1037 ms). It runs here, so it
    // never blocks our main thread, which also draws the bubble and the panel.
    private val foregroundThread = HandlerThread("a11y-foreground").apply { start() }
    private val foregroundHandler = Handler(foregroundThread.looper)
    private val checkPending = AtomicBoolean(false)
    private val foregroundCheck = Runnable {
        // Cleared before the lookup: an event arriving during it queues a fresh check.
        checkPending.set(false)
        Tracing.section(Tracing.A11Y_FOREGROUND_CHECK) {
            guarded {
                val app = foreground.track(rootInActiveWindow)
                if (app?.isTarget == true) treeDumper.request()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.connect(screen)
    }

    /**
     * Main thread, on every event: filter, then hand the foreground check to the
     * foreground thread. A check already queued covers this event too — it reads the
     * window as it is when it runs. The chat itself is parsed on demand
     * ([ChatWindowReader.readConversation]) when the user taps the bubble.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (foreground.canSkip(event)) return
        Tracing.section(Tracing.A11Y_EVENT) {
            if (checkPending.compareAndSet(false, true)) foregroundHandler.post(foregroundCheck)
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
        foregroundHandler.removeCallbacksAndMessages(null)
        foregroundThread.quitSafely()
        treeDumper.close()
        AccessibilityBridge.disconnect(screen)
    }
}
