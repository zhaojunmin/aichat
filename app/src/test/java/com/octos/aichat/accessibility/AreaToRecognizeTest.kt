package com.octos.aichat.accessibility

import android.graphics.Rect
import com.octos.aichat.accessibility.ChatWindowReader.Companion.areaToRecognize
import com.octos.aichat.data.Speaker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AreaToRecognizeTest {

    private val w = 1224
    private val h = 2700

    private fun bubble(l: Int, t: Int, r: Int, b: Int) =
        MessageBounds(Rect(l, t, r, b), Speaker.OTHER)

    @Test fun noBoundsNoKeyboardIsTheWholeScreen() {
        assertEquals(Rect(0, 0, w, h), areaToRecognize(emptyList(), w, h, null))
    }

    @Test fun noBoundsStopsAtTheKeyboard() {
        assertEquals(Rect(0, 0, w, 1812), areaToRecognize(emptyList(), w, h, 1812))
    }

    /** While sliding in the keyboard reports where it is right now (seen: top 2637). */
    @Test fun keyboardSlidingInCutsWhereItIsNow() {
        assertEquals(Rect(0, 0, w, 2637), areaToRecognize(emptyList(), w, h, 2637))
    }

    @Test fun keyboardTopOutsideTheScreenIsIgnored() {
        assertEquals(Rect(0, 0, w, h), areaToRecognize(emptyList(), w, h, 2900))
        assertEquals(Rect(0, 0, w, h), areaToRecognize(emptyList(), w, h, 0))
    }

    @Test fun boundsNarrowItToWhatTheBubblesCover() {
        val bounds = listOf(bubble(40, 400, 700, 520), bubble(500, 600, 1180, 900))
        assertEquals(Rect(40, 400, 1180, 900), areaToRecognize(bounds, w, h, null))
    }

    @Test fun boundsBehindTheKeyboardAreCutAtIt() {
        val bounds = listOf(bubble(40, 400, 700, 520), bubble(500, 1700, 1180, 2000))
        assertEquals(Rect(40, 400, 1180, 1812), areaToRecognize(bounds, w, h, 1812))
    }

    @Test fun boundsOffScreenFallBackToTheWholeScreen() {
        val bounds = listOf(bubble(40, 2800, 700, 2900))
        assertEquals(Rect(0, 0, w, h), areaToRecognize(bounds, w, h, null))
    }
}
