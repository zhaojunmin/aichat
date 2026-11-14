package com.octos.aichat.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagEventTest {

    @Test fun lineRoundTrips() {
        val e = DiagEvent(
            atMs = 1763173263123, code = ErrorCode.A11Y_304, cause = "IllegalStateException",
            thread = "main", ms = 31, http = 402, platform = 3, pkg = "com.tencent.mm",
            exit = "ANR", exitAt = 1763170012000,
        )
        assertEquals(e, DiagEvent.parse(e.toLine()))
        assertEquals("1763173263123\tA11Y-304\tERROR\tcause=IllegalStateException thread=main ms=31 http=402 platform=3 " +
            "pkg=com.tencent.mm exit=ANR exitAt=1763170012000", e.toLine())
    }

    @Test fun minimalLine() {
        val e = DiagEvent(atMs = 1, code = ErrorCode.AI_108)
        assertEquals("1\tAI-108\tERROR\t", e.toLine())
        assertEquals(e, DiagEvent.parse(e.toLine()))
    }

    @Test fun malformedOrUnknownLinesAreSkipped() {
        assertNull(DiagEvent.parse(""))
        assertNull(DiagEvent.parse("x\tAI-108\tERROR\t"))
        assertNull(DiagEvent.parse("1\tZZ-999\tERROR\t"))
    }

    @Test fun safeNameKeepsIdentifiersOnly() {
        assertEquals("SocketTimeoutException", DiagEvent.safeName("SocketTimeoutException"))
        assertEquals("DefaultDispatcher-worker-3", DiagEvent.safeName("DefaultDispatcher-worker-3"))
        assertNull(DiagEvent.safeName("周末去爬山"))
        assertNull(DiagEvent.safeName("has space"))
        assertNull(DiagEvent.safeName("tab\there"))
        assertNull(DiagEvent.safeName(""))
    }
}
