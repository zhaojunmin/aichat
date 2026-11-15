package com.octos.aichat.reliability

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.octos.aichat.reliability.Phone.sh
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Black-box reliability scenarios against the installed release app. Each one
 * disturbs the phone the way real life does, checks the app copes, and restores
 * what it changed in `finally` (run-reliability.sh restores again on exit).
 *
 * Preconditions (checked, else skipped): the app's accessibility service and
 * overlay permission are enabled — the state a user runs it in.
 *
 * Deliberately not here: random-input "monkey" runs (they would hit "clear all
 * memories" and wipe the user's data) and `am force-stop` (on some ROMs it
 * silently disables the accessibility service).
 */
@RunWith(AndroidJUnit4::class)
class ReliabilityTests {

    private val startedAt = System.currentTimeMillis()

    @Before fun setUp() {
        Phone.wake()
        assumeTrue("enable the app's accessibility service first", Phone.accessibilityEnabled())
        assumeTrue("allow the app's overlay permission first", Phone.overlayAllowed())
        Phone.openHome() // also auto-starts the bubble service
        Phone.waitUntil("bubble service running", 10_000) { Phone.bubbleRunning() }
        Phone.waitUntil("accessibility service bound", 10_000) { Phone.accessibilityBound() }
    }

    @After fun noNewCrashOrAnr() {
        val reports = Phone.failureReportsSince(startedAt)
        // The crash scenario induces exactly one on purpose.
        assertEquals(
            "crash/ANR reports for $PKG during this test",
            if (inducedCrash) 1 else 0,
            reports,
        )
    }

    private var inducedCrash = false

    /**
     * The process dies unexpectedly: the system must bring both services back on its
     * own, and the app's diagnostics must have recorded it — APP-901 written by the
     * crash handler as it died, APP-902 (exit=CRASH) read from the system on restart.
     */
    @Test fun processCrash_servicesComeBack() {
        inducedCrash = true
        val diagBefore = Phone.diagnosticsExport()
        val before = Phone.pid()
        sh("am crash $PKG")
        Phone.waitUntil("process gone", 5_000) { Phone.pid() != before }
        Phone.device.findObject(androidx.test.uiautomator.By.res("android:id/aerr_close"))?.click()

        val a11yMs = Phone.waitUntil("accessibility service rebound after crash", 30_000) {
            Phone.accessibilityBound()
        }
        val bubbleMs = Phone.waitUntil("bubble service restarted after crash", 60_000) {
            Phone.bubbleRunning()
        }
        assertTrue("crash must not disable the accessibility service", Phone.accessibilityEnabled())
        Phone.report("crash recovery: accessibility ${a11yMs}ms, bubble ${bubbleMs}ms")

        if (diagBefore == null) {
            Phone.report("diagnostics switched off: crash record not checked")
            return
        }
        val diagAfter =
            Phone.diagnosticsExport()
                ?: throw AssertionError("diagnostics switched off during the test")
        assertEquals(
            "APP-901 recorded once",
            count(diagBefore, "APP-901") + 1,
            count(diagAfter, "APP-901"),
        )
        assertTrue(
            "APP-902 recorded on restart",
            count(diagAfter, "APP-902") > count(diagBefore, "APP-902"),
        )
        val newestExit = exitsOf(diagAfter).firstOrNull()
        assertTrue(
            "newest exit should be CRASH, was $newestExit",
            newestExit?.endsWith("CRASH") == true,
        )
        Phone.report("diagnostics: APP-901 + APP-902 exit=CRASH recorded")
    }

    /** "APP-901    3  …" in the counts section; 0 when the code isn't listed. */
    private fun count(report: String, id: String): Int =
        Regex("""(?m)^${Regex.escape(id)}\s+(\d+)\s""").find(report)?.groupValues?.get(1)?.toInt()
            ?: 0

    /** Lines of the exit-reasons section, newest first ("<time>  CRASH"). */
    private fun exitsOf(report: String): List<String> = report.lines().dropWhile {
        !(it.startsWith("== ") && ("退出原因" in it || "exited" in it))
    }.drop(1)
        .takeWhile { it.isNotBlank() }

    /**
     * Memory pressure, foreground and background: no crash, services stay up in the same
     * process.
     */
    @Test fun memoryPressure_noCrash() {
        val pid = Phone.pid()
        for (level in listOf("RUNNING_MODERATE", "RUNNING_LOW", "RUNNING_CRITICAL")) {
            sh("am send-trim-memory $PKG $level")
        }
        Phone.pressHome() // background levels are only accepted for a background process
        for (level in listOf("BACKGROUND", "MODERATE", "COMPLETE")) {
            sh("am send-trim-memory $PKG $level")
        }
        Thread.sleep(2_000)
        assertEquals("process restarted", pid, Phone.pid())
        assertTrue(Phone.bubbleRunning())
        assertTrue(Phone.accessibilityBound())
        Phone.report("memory pressure: same process, services up")
    }

    /**
     * The user revokes the overlay permission while the bubble is showing, then grants it again.
     */
    @Test fun overlayRevoked_noCrash_andRecovers() {
        val pid = Phone.pid()
        try {
            sh("appops set $PKG SYSTEM_ALERT_WINDOW deny")
            Thread.sleep(1_500)
            Phone.openHome()
            assertTrue(
                "home screen should offer to enable the overlay",
                Phone.permissionRowOff(Text.PERM_OVERLAY),
            )
            assertEquals("process restarted", pid, Phone.pid())
        } finally {
            sh("appops set $PKG SYSTEM_ALERT_WINDOW allow")
        }
        Phone.openHome()
        val ms = Phone.waitUntil("bubble service running after re-grant", 10_000) {
            Phone.bubbleRunning()
        }
        assertTrue(
            "home screen should show the overlay as on",
            !Phone.permissionRowOff(Text.PERM_OVERLAY),
        )
        Phone.report("overlay revoked → re-granted: bubble up in ${ms}ms")
    }

    /** The accessibility service is switched off (by the user or the system), then back on. */
    @Test fun accessibilityDisabled_noCrash_andReconnects() {
        val original = Phone.accessibilitySetting()
        val pid = Phone.pid()
        try {
            val others = original.split(':').filter { it.isNotBlank() && it != A11Y_SERVICE }
            if (others.isEmpty()) {
                sh("settings delete secure enabled_accessibility_services")
            } else {
                sh("settings put secure enabled_accessibility_services ${others.joinToString(":")}")
            }
            Phone.waitUntil("accessibility service unbound", 10_000) { !Phone.accessibilityBound() }
            Phone.openHome()
            assertTrue(
                "home screen should offer to enable accessibility",
                Phone.permissionRowOff(Text.PERM_ACCESSIBILITY),
            )
            assertEquals("process restarted", pid, Phone.pid())
            assertTrue("bubble service should keep running", Phone.bubbleRunning())
        } finally {
            sh("settings put secure enabled_accessibility_services $original")
            sh("settings put secure accessibility_enabled 1")
        }
        val ms = Phone.waitUntil("accessibility service rebound", 15_000) {
            Phone.accessibilityBound()
        }
        Phone.openHome()
        assertTrue(
            "home screen should show accessibility as on",
            !Phone.permissionRowOff(Text.PERM_ACCESSIBILITY),
        )
        Phone.report("accessibility off → on: rebound in ${ms}ms")
    }

    /**
     * No network at all (R4). Cloud mode must answer with a readable network error
     * within 10 s — never a spinner that never ends; local mode must still generate.
     */
    @Test fun offline_testConnectionAnswers() {
        val wifiWasOn = sh("settings get global wifi_on").trim() != "0"
        val dataWasOn = sh("settings get global mobile_data").trim() == "1"
        try {
            sh("svc wifi disable")
            sh("svc data disable")
            Phone.waitUntil("network down", 15_000) {
                Regex(
                    """Active default network: none""",
                ).containsMatchIn(sh("dumpsys connectivity"))
            }
            Phone.openHome()
            val button =
                Phone.scrollTo(Text.TEST_CONNECTION)
                    ?: throw AssertionError("test-connection button not found")
            val started = android.os.SystemClock.elapsedRealtime()
            button.click()
            val local = Phone.find(Text.TESTING_LOCAL, timeoutMs = 1_500) != null
            Phone.waitUntil("test result shown", 45_000) {
                Phone.find(Text.TEST_RESULT, timeoutMs = 250) !=
                    null
            }
            val ms = android.os.SystemClock.elapsedRealtime() - started

            val networkError = Phone.findContaining(Text.NETWORK_ERROR) != null
            if (local) {
                assertTrue("local mode must work offline, got a network error", !networkError)
            } else {
                assertTrue("cloud mode offline must say the network failed", networkError)
                assertTrue("cloud error took ${ms}ms (budget 10 s)", ms <= 10_000)
            }
            Phone.find(Text.CLOSE)?.click()
            Phone.report(
                "offline test connection (${if (local) "local" else "cloud"}): answered in ${ms}ms",
            )
        } finally {
            if (wifiWasOn) sh("svc wifi enable")
            if (dataWasOn) sh("svc data enable")
        }
    }

    /** Dark-mode switch and rotation recreate the UI: no crash, same process, home still usable. */
    @Test fun configChanges_noCrash() {
        val pid = Phone.pid()
        val night = sh("cmd uimode night").substringAfter(":").trim() // "yes" / "no" / "auto"
        val autoRotate = sh("settings get system accelerometer_rotation").trim()
        val rotation = sh("settings get system user_rotation").trim()
        try {
            sh("cmd uimode night ${if (night == "yes") "no" else "yes"}")
            Thread.sleep(1_500)
            sh("settings put system accelerometer_rotation 0")
            sh("settings put system user_rotation 1")
            Thread.sleep(1_500)
            assertTrue(
                "home screen gone after config changes",
                Phone.find(Text.PERMISSIONS_TITLE, 5_000) != null,
            )
        } finally {
            sh("cmd uimode night ${night.ifEmpty { "auto" }}")
            sh("settings put system user_rotation ${rotation.toIntOrNull() ?: 0}")
            sh("settings put system accelerometer_rotation ${autoRotate.toIntOrNull() ?: 1}")
        }
        Thread.sleep(1_500)
        assertEquals("process restarted", pid, Phone.pid())
        assertTrue(Phone.find(Text.PERMISSIONS_TITLE, 5_000) != null)
        Phone.report("dark mode + rotation: no crash, same process")
    }
}
