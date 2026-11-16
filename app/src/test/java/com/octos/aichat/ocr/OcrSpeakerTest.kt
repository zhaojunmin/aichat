package com.octos.aichat.ocr

import com.octos.aichat.data.Speaker
import com.octos.aichat.data.SupportedApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSpeakerTest {

    // A right-hugging block: small right margin, large left margin.
    private val right = 600 to 40
    private val left = 40 to 600

    @Test fun greenIsAlwaysMine() {
        assertEquals(Speaker.ME, OcrSpeaker.of(true, true, left.first, left.second))
        assertEquals(Speaker.ME, OcrSpeaker.of(true, false, left.first, left.second))
    }

    /** WeChat: a white card on the right (a file I sent, a system line) is not "me". */
    @Test fun greenOnlyAppsCountEverythingElseAsTheOtherSide() {
        assertEquals(Speaker.OTHER, OcrSpeaker.of(false, true, right.first, right.second))
        assertEquals(Speaker.OTHER, OcrSpeaker.of(false, true, left.first, left.second))
    }

    @Test fun otherAppsFallBackToTheSideTheBubbleHugs() {
        assertEquals(Speaker.ME, OcrSpeaker.of(false, false, right.first, right.second))
        assertEquals(Speaker.OTHER, OcrSpeaker.of(false, false, left.first, left.second))
    }

    @Test fun onlyWeChatIsGreenOnly() {
        assertTrue(SupportedApps.myBubblesGreen(SupportedApps.WECHAT))
        assertFalse(SupportedApps.myBubblesGreen(SupportedApps.MOMO))
        assertFalse(SupportedApps.myBubblesGreen("org.example.chat"))
    }
}
