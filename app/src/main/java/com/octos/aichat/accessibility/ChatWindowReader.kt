package com.octos.aichat.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import com.octos.aichat.data.Conversation
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.Tracing
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * What the overlay needs from the screen on a bubble tap: the chat as parsed from
 * the node tree, or a screenshot ready for OCR (with the app's message bounds, the
 * keyboard cropped away). Built on [service]'s window APIs; holds no state.
 */
class ChatWindowReader(private val service: AccessibilityService) {

    private val screenWidth: Int get() = service.resources.displayMetrics.widthPixels
    private val screenHeight: Int get() = service.resources.displayMetrics.heightPixels

    /**
     * Parse [pkg]'s chat window as it is right now; empty when it can't be read. Off the main
     * thread.
     */
    suspend fun readConversation(pkg: String): Conversation = withContext(Dispatchers.IO) {
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
        service.windows.orEmpty().asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == pkg }
            ?: service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }

    /**
     * Chat message bounds for apps with a [MessageBoundsLocator] rule; empty otherwise. Reads
     * the same window as [readConversation] — [pkg]'s own, not whichever window is active
     * (our overlay or the keyboard may be). A full tree walk, so off the main thread.
     */
    private suspend fun locateMessageBounds(pkg: String): List<MessageBounds> {
        if (!MessageBoundsLocator.hasRule(pkg)) return emptyList()
        return withContext(Dispatchers.IO) {
            MessageBoundsLocator.locate(windowRoot(pkg), screenWidth, pkg)
        }
    }

    /**
     * [pkg]'s chat ready for OCR: the message bounds (apps with a rule) and the pixels,
     * taken back to back, cropped to [areaToRecognize] — the bounds are shifted to match.
     * Null when the screenshot failed.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureScreenshot(pkg: String): ChatScreenshot? {
        val bounds = locateMessageBounds(pkg)
        val screen = takeScreenshot(pkg) ?: return null
        val area = areaToRecognize(bounds, screen.width, screen.height, imeBounds()?.top)
        if (area.width() == screen.width && area.height() == screen.height) {
            return ChatScreenshot(screen, bounds)
        }
        val cropped = Bitmap.createBitmap(screen, area.left, area.top, area.width(), area.height())
        screen.recycle()
        val shifted = bounds.map { b ->
            b.copy(rect = Rect(b.rect).apply { offset(-area.left, -area.top) })
        }
        return ChatScreenshot(cropped, shifted)
    }

    /**
     * Capture the current screen via the accessibility API — no MediaProjection
     * consent dialog needed. Returns a software ARGB_8888 bitmap, or null on failure
     * (e.g. a FLAG_SECURE window, or rate limiting).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(pkg: String): Bitmap? = suspendCancellableCoroutine { cont ->
        // takeScreenshot() can throw synchronously (e.g. SecurityException if the
        // capability is missing). Guard it so a failure never crashes the whole
        // accessibility service process.
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                // Off the main thread: the callback copies the full-screen hardware
                // bitmap into a software one (~13 MB at 1224×2700).
                Dispatchers.Default.asExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val software = try {
                            val hw = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer,
                                result.colorSpace,
                            )
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
                },
            )
        } catch (e: Exception) {
            DiagLog.record(ErrorCode.A11Y_304, cause = e, pkg = pkg)
            if (cont.isActive) cont.resume(null)
        }
    }

    /** Screen bounds of the soft keyboard if it is showing, else null. */
    private fun imeBounds(): Rect? = try {
        service.windows
            ?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
            ?.takeIf { !it.isEmpty }
    } catch (e: Exception) {
        DiagLog.record(ErrorCode.A11Y_306, cause = e)
        null
    }

    companion object {
        /**
         * The part of a [width]×[height] screenshot worth recognising: everything above
         * the keyboard (its letters would be read as chat), narrowed to the area the
         * [bounds] cover when the app gives them (title bar, input bar and panels around
         * the bubbles are dropped before OCR rather than after). A keyboard top outside
         * the screen is ignored; while sliding in, the keyboard reports where it is now.
         * Falls back to the whole screen when nothing is left.
         */
        internal fun areaToRecognize(
            bounds: List<MessageBounds>,
            width: Int,
            height: Int,
            imeTop: Int?,
        ): Rect {
            val screen = Rect(0, 0, width, height)
            val area = Rect(0, 0, width, imeTop?.takeIf { it in 1 until height } ?: height)
            if (bounds.isNotEmpty()) {
                val covered = Rect(bounds.first().rect).apply { bounds.forEach { union(it.rect) } }
                if (!area.intersect(covered)) return screen
            }
            return area.takeIf { !it.isEmpty } ?: screen
        }
    }
}
