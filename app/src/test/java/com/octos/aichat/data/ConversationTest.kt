package com.octos.aichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    private fun conv(vararg lines: Pair<Speaker, String>) =
        Conversation("pkg", lines.map { ChatMessage(it.first, it.second) })

    @Test fun lastFromOther_picksLatestOtherLine() {
        val c = conv(Speaker.OTHER to "a", Speaker.ME to "b", Speaker.OTHER to "c", Speaker.ME to "d")
        assertEquals("c", c.lastFromOther())
    }

    @Test fun lastFromOther_nullWhenOnlyMine() {
        assertNull(conv(Speaker.ME to "x").lastFromOther())
    }

    @Test fun withoutTrailingMine_stripsOnlyTheTail() {
        val c = conv(Speaker.ME to "1", Speaker.OTHER to "2", Speaker.ME to "3", Speaker.ME to "4")
        assertEquals(listOf("1", "2"), c.withoutTrailingMine().messages.map { it.text })
    }

    @Test fun withoutTrailingMine_returnsSameInstanceWhenNothingToStrip() {
        val c = conv(Speaker.ME to "1", Speaker.OTHER to "2")
        assertSame(c, c.withoutTrailingMine())
    }

    @Test fun withoutTrailingMine_allMineBecomesEmpty() {
        assertTrue(conv(Speaker.ME to "1", Speaker.ME to "2").withoutTrailingMine().isEmpty)
    }

    @Test fun toTranscript_labelsSpeakersAndKeepsTail() {
        val c = conv(Speaker.OTHER to "a", Speaker.ME to "b", Speaker.OTHER to "c")
        assertEquals("对方: a\n我: b\n对方: c", c.toTranscript())
        assertEquals("我: b\n对方: c", c.toTranscript(maxLines = 2))
    }

    @Test fun empty_isEmpty() {
        assertTrue(Conversation.empty().isEmpty)
        assertEquals("", Conversation.empty().toTranscript())
    }
}
