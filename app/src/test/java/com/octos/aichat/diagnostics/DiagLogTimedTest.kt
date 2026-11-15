package com.octos.aichat.diagnostics

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagLogTimedTest {

    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() = DiagLog.setEnabled(false)

    @Test fun countsOnlyCompletedBlocks() {
        val recorder = DiagnosticsRecorder(DiagnosticsStore(File(tmp.root, "diag"))).apply {
            setEnabled(true)
        }
        DiagLog.install(recorder)

        assertEquals("ok", DiagLog.timed(TimedOp.GENERATE_CLOUD) { "ok" })
        try {
            DiagLog.timed(TimedOp.GENERATE_CLOUD) { error("network down") }
            fail("expected the failure to propagate")
        } catch (_: IllegalStateException) {
        }

        assertEquals(1, recorder.durationsSnapshot()[TimedOp.GENERATE_CLOUD]!!.sum())
    }
}
