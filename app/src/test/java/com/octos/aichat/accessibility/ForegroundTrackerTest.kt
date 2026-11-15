package com.octos.aichat.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundTrackerTest {

    private val soul = "cn.soulapp.android"
    private val launcher = "com.hihonor.android.launcher"
    private var targets = setOf(soul)
    private val published = mutableListOf<ForegroundApp>()
    private val tracker = ForegroundTracker({ it in targets }, { false }, { published += it })

    private fun root(pkg: String) = AccessibilityNodeInfo().apply { packageName = pkg }
    private fun event(type: Int, pkg: String) = AccessibilityEvent(type).apply { packageName = pkg }

    @Test fun publishesTheActiveWindowsAppAndWhetherItIsATarget() {
        assertEquals(ForegroundApp(soul, true), tracker.track(root(soul)))
        assertEquals(ForegroundApp(launcher, false), tracker.track(root(launcher)))
        assertEquals(listOf(ForegroundApp(soul, true), ForegroundApp(launcher, false)), published)
    }

    /** A null root (transitions, keyboard opening) must not be read as "left the chat app". */
    @Test fun nullRootKeepsTheLastKnownState() {
        tracker.track(root(soul))
        assertNull(tracker.track(null))
        assertEquals(listOf(ForegroundApp(soul, true)), published)
        assertTrue(tracker.canSkip(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, soul)))
    }

    @Test fun reusesTheSameValueWhileNothingChanges() {
        val first = tracker.track(root(soul))
        assertSame(first, tracker.track(root(soul)))
    }

    @Test fun anEditedTargetListAppliesWithoutSwitchingApps() {
        tracker.track(root(soul))
        targets = emptySet()
        assertEquals(ForegroundApp(soul, false), tracker.track(root(soul)))
    }

    @Test fun skipsOnlyRoutineEventsOfTheTrackedApp() {
        assertFalse(
            "nothing known yet",
            tracker.canSkip(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, soul)),
        )
        tracker.track(root(soul))
        assertTrue(tracker.canSkip(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, soul)))
        assertFalse(tracker.canSkip(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, soul)))
        assertFalse(tracker.canSkip(event(AccessibilityEvent.TYPE_VIEW_SCROLLED, launcher)))
    }
}
