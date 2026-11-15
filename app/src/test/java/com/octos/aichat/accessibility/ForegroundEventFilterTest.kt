package com.octos.aichat.accessibility

import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundEventFilterTest {

    private val soul = "cn.soulapp.android"

    private fun skip(type: Int, pkg: CharSequence?, fg: String? = soul, capture: Boolean = false) =
        isRoutineUpdateOfForegroundApp(type, pkg, fg, capture)

    @Test fun scrollAndContentChangesOfForegroundAppAreSkipped() {
        assertTrue(skip(TYPE_VIEW_SCROLLED, soul))
        assertTrue(skip(TYPE_WINDOW_CONTENT_CHANGED, soul))
        // packageName arrives as a CharSequence, not necessarily a String.
        assertTrue(skip(TYPE_WINDOW_CONTENT_CHANGED, StringBuilder(soul)))
    }

    /** A window-state change may be a switch to another app: always check. */
    @Test fun windowStateChangeIsNeverSkipped() {
        assertFalse(skip(TYPE_WINDOW_STATE_CHANGED, soul))
    }

    /** IME, system UI or a newly opened app: the foreground may have changed. */
    @Test fun eventsFromAnotherPackageAreChecked() {
        assertFalse(skip(TYPE_WINDOW_CONTENT_CHANGED, "com.sohu.inputmethod.sogou"))
        assertFalse(skip(TYPE_VIEW_SCROLLED, "com.tencent.mm"))
    }

    @Test fun unknownStateIsChecked() {
        // nothing known yet (e.g. right after start)
        assertFalse(skip(TYPE_VIEW_SCROLLED, soul, fg = null))
        assertFalse(skip(TYPE_VIEW_SCROLLED, null))
    }

    /** The debug raw-tree capture refreshes from these events, so keep them while it's on. */
    @Test fun nothingSkippedWhileDebugCaptureIsOn() {
        assertFalse(skip(TYPE_VIEW_SCROLLED, soul, capture = true))
    }

    @Test fun otherEventTypesAreChecked() {
        assertFalse(skip(TYPE_VIEW_CLICKED, soul))
    }
}
