package com.octos.aichat.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** The app owning the active window, and whether it's one we assist in. */
data class ForegroundApp(val pkg: String, val isTarget: Boolean)

/**
 * Works out which app is in the foreground from accessibility events, cheaply
 * enough to run on the main thread for every event. Owns the last known
 * foreground; each change goes to [publish] (which shows/hides the bubble).
 */
class ForegroundTracker(
    private val isTarget: (String) -> Boolean,
    private val dumpingRawTree: () -> Boolean,
    private val publish: (ForegroundApp) -> Unit,
) {
    private var current: ForegroundApp? = null

    /** True when the event can't change the foreground, so [track] can be skipped. */
    fun canSkip(event: AccessibilityEvent): Boolean = isRoutineUpdateOfForegroundApp(
        event.eventType,
        event.packageName,
        current?.pkg,
        dumpingRawTree(),
    )

    /**
     * Drive foreground state only from the *active window's* package. The IME,
     * toasts and transient sub-windows don't own the active window, so this avoids
     * false "left the chat app" signals (which would hide the bubble).
     *
     * Crucially, when [root] is momentarily null (common during transitions / when
     * the keyboard opens) we must NOT fall back to the event's package — that often
     * belongs to the IME or a system window, which would wrongly hide the bubble and
     * leave it stuck hidden. Skipping keeps the last known state until a reliable one.
     *
     * @return the foreground app, or null when it couldn't be determined.
     */
    fun track(root: AccessibilityNodeInfo?): ForegroundApp? {
        val pkg = root?.packageName?.toString() ?: return null
        // Re-evaluated every time so an edited target list applies at once; only
        // allocate when something actually changed.
        val target = isTarget(pkg)
        val app =
            current?.takeIf { it.pkg == pkg && it.isTarget == target } ?: ForegroundApp(pkg, target)
        current = app
        publish(app)
        return app
    }
}

/**
 * True when [eventType] is a scroll / content change coming from the app already
 * known to be in front ([foregroundPackage]): such an event can't change which app
 * is in the foreground, so the main thread can skip `getRootInActiveWindow`
 * — a cross-process round trip served by the chat app's busy UI thread (7 ms P50,
 * 18 ms P95 while scrolling), which was ~95% of the per-event cost.
 *
 * Window-state changes and events from any other package (IME, system UI, a newly
 * opened app) still take the full check, as does everything while the debug
 * raw-tree capture is on (it refreshes from these events).
 */
internal fun isRoutineUpdateOfForegroundApp(
    eventType: Int,
    eventPackage: CharSequence?,
    foregroundPackage: String?,
    captureRawTree: Boolean,
): Boolean {
    if (captureRawTree || foregroundPackage == null || eventPackage == null) return false
    if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
        eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
    ) {
        return false
    }
    return eventPackage.contentEquals(foregroundPackage)
}
