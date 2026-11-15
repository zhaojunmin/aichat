package com.octos.aichat.data.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplyLogTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.filesDir, "my_replies.txt")
    private val prefs = context.getSharedPreferences("ai_chat_memory", Context.MODE_PRIVATE)
    private lateinit var db: MemoryDatabase

    @Before fun setUp() {
        file.delete()
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    private fun newLog() = ReplyLog(context, db.learningStateDao())

    @Test fun appendNormalisesAndDedups() = runBlocking {
        val log = newLog()
        assertTrue(log.append("  周末\n去爬山  "))
        assertFalse("same text again", log.append("周末 去爬山"))
        assertFalse("blank", log.append(" \n "))
        assertEquals(listOf("周末 去爬山"), log.pending().replies)
    }

    @Test fun offsetAndDedupSurviveARestart() = runBlocking {
        val log = newLog()
        log.append("a")
        log.append("b")
        log.append("c")
        log.markLearned(2)

        val restarted = newLog()
        assertEquals(ReplyCounts(total = 3, learned = 2), restarted.primeRecent())
        assertEquals(listOf("c"), restarted.pending().replies)
        assertFalse("dedup primed from the file", restarted.append("b"))
    }

    /** Upgrade from a version that kept the offset in SharedPreferences. */
    @Test fun legacyPrefsOffsetIsMovedIntoTheDatabase() = runBlocking {
        newLog().apply {
            append("a")
            append("b")
            append("c")
        } // append never writes the offset row
        prefs.edit().putInt("learned_reply_count", 2).commit()

        assertEquals(listOf("c"), newLog().pending().replies)
        assertEquals(2, db.learningStateDao().learnedReplies())
        assertFalse("old key removed", prefs.contains("learned_reply_count"))
    }

    @Test fun offsetBeyondTheFileIsClamped() = runBlocking {
        val log = newLog()
        log.append("a")
        log.markLearned(10) // e.g. the file was cleared by hand
        assertEquals(PendingReplies(emptyList(), 1), log.pending())
    }

    @Test fun clearResetsFileOffsetAndDedup() = runBlocking {
        val log = newLog()
        log.append("a")
        log.markLearned(1)
        log.clear()

        assertFalse(file.exists())
        assertEquals(0, db.learningStateDao().learnedReplies())
        assertTrue("dedup cleared", log.append("a"))
        assertEquals(listOf("a"), log.pending().replies)
    }
}
