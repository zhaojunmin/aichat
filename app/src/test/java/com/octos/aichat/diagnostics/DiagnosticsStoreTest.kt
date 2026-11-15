package com.octos.aichat.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun dir() = File(tmp.root, "diagnostics")

    @Test fun appendAndReadRecent() {
        val store = DiagnosticsStore(dir())
        store.append(listOf("a", "b"))
        store.append(listOf("c"))
        assertEquals(listOf("b", "c"), store.recent(2))
        assertEquals(listOf("a", "b", "c"), store.recent(10))
    }

    @Test fun rotatesBySizeAndKeepsTwoFiles() {
        val store = DiagnosticsStore(dir(), maxBytes = 20)
        repeat(10) { store.append(listOf("line-$it")) } // 7 bytes each
        val files = dir().listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("events.log", "events.1.log"), files)
        assertTrue("both files bounded", dir().listFiles()!!.all { it.length() <= 20 + 7 })
        assertEquals("line-9", store.recent(1).single())
        assertFalse("oldest dropped", "line-0" in store.recent(100))
    }

    @Test fun summaryRoundTripsAndSurvivesARestart() {
        val s = DiagSummary(mapOf(ErrorCode.AI_108 to 12, ErrorCode.APP_901 to 1), exitSeenAt = 99)
        DiagnosticsStore(dir()).writeSummary(s)
        assertEquals(s, DiagnosticsStore(dir()).readSummary())
    }

    @Test fun durationsRoundTrip() {
        val buckets = List(DurationBuckets.SIZE) { it }
        val s = DiagSummary(durations = mapOf(TimedOp.OCR to buckets))
        DiagnosticsStore(dir()).writeSummary(s)
        assertEquals(s, DiagnosticsStore(dir()).readSummary())
    }

    @Test fun corruptSummaryLinesAreIgnored() {
        dir().mkdirs()
        File(dir(), "summary").writeText(
            "exitSeenAt\t5\nAI-108\t3\ngarbage\nZZ-999\t4\nAI-101\tx\n" +
                "T:OCR\t1,2\nT:NOPE\t0,0,0,0,0,0,0,0,0\nT:PARSE\t1,x,0,0,0,0,0,0,0\n"
        )
        assertEquals(DiagSummary(mapOf(ErrorCode.AI_108 to 3), exitSeenAt = 5), DiagnosticsStore(dir()).readSummary())
    }

    @Test fun clearDeletesEverything() {
        val store = DiagnosticsStore(dir())
        store.append(listOf("a"))
        store.writeSummary(DiagSummary(mapOf(ErrorCode.AI_108 to 1)))
        store.clear()
        assertEquals(emptyList<File>(), dir().listFiles()!!.toList())
        assertEquals(DiagSummary(), store.readSummary())
        assertEquals(emptyList<String>(), store.recent(10))
    }
}
