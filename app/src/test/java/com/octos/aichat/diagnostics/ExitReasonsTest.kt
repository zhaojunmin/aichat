package com.octos.aichat.diagnostics

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ExitReasonsTest {

    @Test fun namesAreStable() {
        assertEquals("CRASH", ExitReasons.nameOf(ApplicationExitInfo.REASON_CRASH))
        assertEquals("CRASH_NATIVE", ExitReasons.nameOf(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("ANR", ExitReasons.nameOf(ApplicationExitInfo.REASON_ANR))
        assertEquals("LOW_MEMORY", ExitReasons.nameOf(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("USER_STOPPED", ExitReasons.nameOf(ApplicationExitInfo.REASON_USER_STOPPED))
    }

    @Test fun unknownNumbersStayReadable() {
        assertEquals("REASON_999", ExitReasons.nameOf(999))
    }
}
