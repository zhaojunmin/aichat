package com.octos.aichat.diagnostics

import java.io.File
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "diagnostics")
    private var now = 1_000L
    private fun recorder(enabled: Boolean = true) =
        DiagnosticsRecorder(DiagnosticsStore(dir), clock = { now }).apply { setEnabled(enabled) }

    private fun storedText(): String = dir.listFiles().orEmpty().joinToString("\n") {
        it.readText()
    }

    @Test fun offRecordsNothing() {
        val r = recorder(enabled = false)
        r.record(ErrorCode.AI_108)
        r.recordCrash(IllegalStateException(), Thread.currentThread())
        r.recordExits(listOf(ExitRecord("CRASH", 5_000)), sinceMs = 0)
        r.flush()
        assertEquals(emptyMap<ErrorCode, Int>(), r.countsSnapshot())
        assertFalse(dir.exists() && dir.listFiles()!!.isNotEmpty())
    }

    @Test fun recordsCountsAndFlushes() {
        val r = recorder()
        r.record(ErrorCode.AI_108, cause = UnknownHostException(), ms = 31)
        r.record(ErrorCode.AI_108)
        r.record(ErrorCode.BUB_501)
        assertEquals(mapOf(ErrorCode.AI_108 to 2, ErrorCode.BUB_501 to 1), r.countsSnapshot())

        val events = r.recent(10)
        assertEquals(
            listOf(ErrorCode.AI_108, ErrorCode.AI_108, ErrorCode.BUB_501),
            events.map {
                it.code
            },
        )
        assertEquals("UnknownHostException", events[0].cause)
        assertEquals(31L, events[0].ms)
        assertEquals(1_000L, events[0].atMs)
    }

    @Test fun countersSurviveARestart_andLoadAddsToEarlyEvents() {
        recorder().apply {
            record(ErrorCode.AI_108)
            record(ErrorCode.AI_108)
            flush()
        }

        val restarted = recorder()
        restarted.record(ErrorCode.AI_108) // before load() ran on the background thread
        restarted.load()
        assertEquals(mapOf(ErrorCode.AI_108 to 3), restarted.countsSnapshot())
    }

    /** The privacy contract: exception messages never reach the record. */
    @Test fun onlyTheExceptionClassIsKept() {
        val r = recorder()
        r.record(ErrorCode.AI_109, cause = IllegalStateException("SENTINEL 周末去爬山 sk-SECRET"))
        r.recordCrash(RuntimeException("SENTINEL crash message"), Thread("worker-1"))
        val text = storedText()
        assertFalse(text, "SENTINEL" in text)
        assertFalse(text, "周末" in text)
        assertTrue("IllegalStateException" in text)
        assertTrue("thread=worker-1" in text)
    }

    @Test fun packageNameOnlyForReadingAndOcrCodes() {
        val r = recorder()
        r.record(ErrorCode.A11Y_302, pkg = "com.tencent.mm")
        r.record(ErrorCode.OCR_402, pkg = "com.tencent.mm")
        r.record(ErrorCode.AI_108, pkg = "com.tencent.mm")
        r.record(ErrorCode.A11Y_303, pkg = "not a package!")
        assertEquals(
            listOf("com.tencent.mm", "com.tencent.mm", null, null),
            r.recent(10).map {
                it.pkg
            },
        )
    }

    @Test fun crashIsWrittenSynchronously() {
        val r = recorder()
        r.recordCrash(IllegalStateException(), Thread("main"))
        // No flush() call: the crash path writes before the process dies.
        assertTrue(storedText().contains("APP-901"))
    }

    @Test fun exitsAreRecordedOnceAndOnlyAfterSince() {
        val r = recorder()
        val exits =
            listOf(
                ExitRecord("CRASH", 3_000),
                ExitRecord("ANR", 6_000),
                ExitRecord("LOW_MEMORY", 9_000),
            )
        r.recordExits(exits, sinceMs = 5_000)
        r.recordExits(exits, sinceMs = 5_000) // next start: same system records
        assertEquals(
            listOf("ANR" to 6_000L, "LOW_MEMORY" to 9_000L),
            r.recent(10).map {
                it.exit to
                    it.exitAt
            },
        )

        r.flush()
        val restarted = recorder().apply { load() }
        restarted.recordExits(exits + ExitRecord("CRASH", 12_000), sinceMs = 5_000)
        assertEquals(
            "only the new one",
            listOf("CRASH"),
            restarted.recent(10).drop(2).map {
                it.exit
            },
        )
    }

    @Test fun wipeDeletesEverything() {
        val r = recorder()
        r.record(ErrorCode.AI_108)
        r.recordExits(listOf(ExitRecord("CRASH", 3_000)), sinceMs = 0)
        r.flush()
        r.setEnabled(false)
        r.wipe()

        assertEquals(emptyMap<ErrorCode, Int>(), r.countsSnapshot())
        assertEquals(emptyList<File>(), dir.listFiles()!!.toList())

        // Re-enabled: a fresh record — the old exit is only re-read if after the new "since".
        r.setEnabled(true)
        r.recordExits(listOf(ExitRecord("CRASH", 3_000)), sinceMs = 4_000)
        assertEquals(emptyList<DiagEvent>(), r.recent(10))
    }

    @Test fun durationsAreBucketedPersistedAndWiped() {
        val r = recorder()
        r.recordDuration(TimedOp.TAP_TO_RESULT, 1_800)
        r.recordDuration(TimedOp.TAP_TO_RESULT, 2_400)
        r.recordDuration(TimedOp.OCR, 40)
        r.recordDuration(TimedOp.OCR, -1) // clock went backwards: ignored
        assertEquals(2, r.durationsSnapshot()[TimedOp.TAP_TO_RESULT]!!.sum())
        assertEquals(
            1,
            r.durationsSnapshot()[TimedOp.TAP_TO_RESULT]!![DurationBuckets.indexOf(1_800)],
        )
        assertEquals(1, r.durationsSnapshot()[TimedOp.OCR]!!.sum())
        r.flush()

        val restarted = recorder().apply { load() }
        restarted.recordDuration(TimedOp.OCR, 60)
        assertEquals(2, restarted.durationsSnapshot()[TimedOp.OCR]!!.sum())

        restarted.wipe()
        assertEquals(emptyMap<TimedOp, List<Int>>(), restarted.durationsSnapshot())
    }

    @Test fun durationsNotCountedWhenOff() {
        val r = recorder(enabled = false)
        r.recordDuration(TimedOp.LEARN, 500)
        assertEquals(emptyMap<TimedOp, List<Int>>(), r.durationsSnapshot())
    }

    @Test fun asksForAFlushOnceEnoughArePending() {
        val r = recorder()
        var asked = 0
        r.onPendingFull = { asked++ }
        repeat(DiagnosticsRecorder.FLUSH_AT - 1) { r.record(ErrorCode.AI_108) }
        assertEquals(0, asked)
        r.record(ErrorCode.AI_108)
        assertEquals(1, asked)
        r.flush()
        assertNull("pending drained", r.recent(100).firstOrNull { it.code != ErrorCode.AI_108 })
        assertEquals(DiagnosticsRecorder.FLUSH_AT, r.recent(100).size)
    }
}
