package com.octos.aichat.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.octos.aichat.AppBus
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.SettingsStore
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.Tracing
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The platform adapter: receives accessibility callbacks and hands them to the
 * pieces that act on them, and implements [ChatWindowReader] on top of the
 * service's window APIs.
 *
 * - [ForegroundTracker]: which app is in front (shows / hides the bubble), per event.
 * - [ChatWindowReader]: reading the chat on bubble tap (node tree, or screenshot for OCR).
 * - [ReplyFiller]: dropping the chosen reply into the input box.
 * - [RawTreeDumper]: debug-only node tree capture.
 */
class ChatAccessibilityService : AccessibilityService(), ChatWindowReader {

    private val settings: SettingsStore by lazy { SettingsRepository.get(this) }
    private val isTarget: (String) -> Boolean = { it in settings.current().targetPackageSet }

    private val foreground = ForegroundTracker(isTarget, { AppBus.captureRawTree }, AppBus::onForeground)
    private val treeDumper = RawTreeDumper({ rootInActiveWindow }, isTarget)
    private val replyFiller = ReplyFiller { rootInActiveWindow }

    private val screenWidth: Int get() = resources.displayMetrics.widthPixels
    private val screenHeight: Int get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppBus.attachAccessibility(this, replyFiller)
    }

    /**
     * Main thread, on every event: cheap foreground tracking only. The chat itself
     * is parsed on demand ([readConversation]) when the user taps the bubble — no
     * per-event tree walks while they're just chatting.
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
        AppBus.detachAccessibility(this)
    }

    // ---- ChatWindowReader -------------------------------------------------

    /** The tree walk runs off the main thread. */
    override suspend fun readConversation(pkg: String): Conversation = withContext(Dispatchers.IO) {
        try {
            Tracing.section(Tracing.A11Y_PARSE) {
                val root = windowRoot(pkg) ?: run {
                    DiagLog.record(ErrorCode.A11Y_303, pkg = pkg)
                    return@withContext Conversation(pkg, emptyList())
                }
                ChatParser.parse(root, screenWidth, screenHeight, pkg)
            }
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.A11Y_302, cause = e, pkg = pkg)
            Conversation(pkg, emptyList())
        }
    }

    /**
     * Root of [pkg]'s app window. Looked up by package rather than "active window"
     * because the tap itself lands on our overlay; falls back to the active window.
     */
    private fun windowRoot(pkg: String): AccessibilityNodeInfo? =
        // getRoot() is a cross-process call: fetch it once per window.
        windows.orEmpty().asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == pkg }
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }

    /** A full tree walk (one IPC per node), so off the main thread. */
    override suspend fun locateBubbles(pkg: String): List<BubbleBox> {
        if (!BubbleLocator.hasRule(pkg)) return emptyList()
        return withContext(Dispatchers.IO) { BubbleLocator.locate(rootInActiveWindow, screenWidth, pkg) }
    }

    /**
     * Capture the current screen via the accessibility API — no MediaProjection
     * consent dialog needed. Returns a software ARGB_8888 bitmap ready for OCR,
     * or null on failure (e.g. a FLAG_SECURE window, or rate limiting).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun captureScreenshot(pkg: String): Bitmap? = suspendCancellableCoroutine { cont ->
        // takeScreenshot() can throw synchronously (e.g. SecurityException if the
        // capability is missing). Guard it so a failure never crashes the whole
        // accessibility service process.
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                // Off the main thread: the callback copies the full-screen hardware
                // bitmap into a software one (~13 MB at 1224×2700).
                Dispatchers.Default.asExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val software = try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            copy
                        } catch (e: Exception) {
                            DiagLog.record(ErrorCode.A11Y_304, cause = e, pkg = pkg)
                            null
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        // A cancelled tap must not leak the ~13 MB copy.
                        cont.resume(software) { software?.recycle() }
                    }

                    override fun onFailure(errorCode: Int) {
                        DiagLog.record(ErrorCode.A11Y_304, platform = errorCode, pkg = pkg)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.A11Y_304, cause = e, pkg = pkg)
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Used to crop the keyboard out of OCR screenshots so its letters aren't read as chat. */
    override fun imeBounds(): Rect? = try {
        windows
            ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
            ?.takeIf { !it.isEmpty }
    } catch (e: Exception) {
        DiagLog.record(ErrorCode.A11Y_306, cause = e)
        null
    }
}
