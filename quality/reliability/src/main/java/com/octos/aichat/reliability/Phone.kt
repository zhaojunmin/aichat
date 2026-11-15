package com.octos.aichat.reliability

import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

const val PKG = "com.octos.aichat"
const val A11Y_SERVICE = "$PKG/$PKG.accessibility.ChatAccessibilityService"

/**
 * The phone as seen from outside the app: shell commands (run with shell
 * privileges through UiAutomation, no root needed) and the app's UI via UiAutomator.
 * Note: commands are split on spaces, not run by a shell — no pipes or quoting.
 */
object Phone {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // By default UiAutomation suppresses every other accessibility service while it
    // is connected — which would switch off the very service under test. Must be
    // set before the first UiAutomation use.
    val device: UiDevice = run {
        Configurator.getInstance().uiAutomationFlags =
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        UiDevice.getInstance(instrumentation)
    }

    fun sh(cmd: String): String = device.executeShellCommand(cmd)

    // ---- process & services ------------------------------------------------

    fun pid(): Int? = sh("pidof $PKG").trim().split(" ").firstOrNull()?.toIntOrNull()

    /**
     * The accessibility manager currently has our service bound. Read from
     * `dumpsys accessibility` ("Bound services" lists labels, not components):
     * the activity manager's service record lingers after an unbind, so it can't tell.
     */
    fun accessibilityBound(): Boolean = sh("dumpsys accessibility").lineSequence()
        .filter { it.trimStart().startsWith("Bound services") }
        .any { line -> Text.A11Y_LABEL.any { "label=$it" in line } }

    /** The app has an overlay window (the floating bubble) on screen. */
    fun bubbleShowing(): Boolean = sh("dumpsys window windows").split("Window #")
        .any { PKG in it.substringBefore('\n') && "APPLICATION_OVERLAY" in it }

    fun accessibilitySetting(): String = sh("settings get secure enabled_accessibility_services")
        .trim()
        .takeUnless { it == "null" }
        .orEmpty()

    fun accessibilityEnabled(): Boolean = A11Y_SERVICE in accessibilitySetting().split(':')

    fun overlayAllowed(): Boolean = sh("appops get $PKG SYSTEM_ALERT_WINDOW").contains("allow")

    /**
     * Crash + ANR reports the system recorded for the app at or after [sinceMs]
     * (wall clock). Catches a crash even when the app restarted itself. (logcat
     * isn't usable: some ROMs drop release apps' logs.)
     */
    fun failureReportsSince(sinceMs: Long): Int {
        // device time zone, like dropbox
        val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val since = sinceMs - 1_000 // stamps have 1 s resolution
        return listOf("data_app_crash", "data_app_anr").sumOf { tag ->
            // Each entry: "<yyyy-MM-dd HH:mm:ss> <tag> (text, N bytes)" then the report.
            val entryStart = Regex("""(?m)^(?=\d{4}-\d\d-\d\d \d\d:\d\d:\d\d $tag )""")
            dropbox(tag).split(entryStart).count { entry ->
                val at = runCatching { stampFormat.parse(entry.take(19))?.time }.getOrNull()
                    ?: return@count false
                at >= since && Regex("""Process: ${Regex.escape(PKG)}\b""").containsMatchIn(entry)
            }
        }
    }

    /** Full dropbox dump for [tag]; retried because a shell read occasionally comes back empty. */
    private fun dropbox(tag: String): String {
        repeat(3) {
            val out = sh("dumpsys dropbox --print $tag")
            if ("Drop box contents" in out) return out
            SystemClock.sleep(300)
        }
        throw AssertionError("could not read dropbox ($tag)")
    }

    // ---- app UI ------------------------------------------------------------

    fun wake() {
        sh("input keyevent KEYCODE_WAKEUP")
        sh("wm dismiss-keyguard")
    }

    /** Open the app on its home screen without killing the process (unlike `am start -S`). */
    fun openHome() {
        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK: fresh home screen, same process.
        sh("am start -W -f 0x10008000 -n $PKG/.ui.MainActivity")
        if (find(Text.PERMISSIONS_TITLE, timeoutMs = 5_000) == null) {
            throw AssertionError("app home screen did not appear")
        }
    }

    fun pressHome() = device.pressHome()

    fun find(texts: List<String>, timeoutMs: Long = 2_000): UiObject2? =
        device.wait(Until.findObject(By.text(anyOf(texts))), timeoutMs)

    /**
     * Scroll the current screen down until one of [texts] shows up. Returns it once the
     * screen has settled: a tap during the fling after a swipe only stops the fling.
     */
    fun scrollTo(texts: List<String>): UiObject2? = scrollUntil { find(texts, timeoutMs = 500) }

    private fun scrollUntil(lookup: () -> UiObject2?): UiObject2? {
        repeat(8) {
            if (lookup() != null) {
                device.waitForIdle()
                SystemClock.sleep(300)
                return lookup()
            }
            swipeUp()
        }
        return null
    }

    private fun swipeUp() {
        val w = device.displayWidth
        val h = device.displayHeight
        device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 40)
    }

    /** Whether the permission row labelled [label] currently offers an "enable" button. */
    fun permissionRowOff(label: List<String>): Boolean {
        // Changing accessibility settings can briefly disturb UiAutomator: look again once.
        val row = scrollTo(label) ?: run {
            openHome()
            scrollTo(label)
        }
            ?: throw AssertionError("permission row $label not found")
        val y = row.visibleBounds.centerY()
        val slack = row.visibleBounds.height()
        return device.findObjects(By.text(anyOf(Text.ENABLE)))
            .any { kotlin.math.abs(it.visibleCenter.y - y) <= slack }
    }

    /**
     * The app's diagnostics export (advanced settings → export), or null when the
     * user has diagnostics switched off. Leaves the app on its home screen.
     */
    fun diagnosticsExport(): String? {
        // Right after a crash the system may still show its "app stopped" dialog.
        device.findObject(By.res("android:id/aerr_close"))?.click()
        openHome()
        // Just after a restart a tap can land before the screen is ready: confirm the
        // advanced screen opened (its "Appearance" card), and tap again if not.
        var opened = false
        for (attempt in 1..3) {
            val entry = scrollToContaining(Text.ADVANCED_ENTRY)
                ?: throw AssertionError("advanced settings entry not found")
            entry.click()
            if (find(Text.ADVANCED_MARKER, timeoutMs = 3_000) != null) {
                opened = true
                break
            }
            openHome()
        }
        if (!opened) throw AssertionError("advanced settings did not open")
        val export = scrollTo(Text.DIAG_EXPORT) ?: throw AssertionError("export button not found")
        // Compose puts `enabled` on the clickable button, not on its text.
        var button: UiObject2? = export
        while (button != null && !button.isClickable) button = button.parent
        if (button?.isEnabled != true) {
            openHome()
            return null
        }
        button.click()
        val report = device.wait(Until.findObject(By.textStartsWith("== ")), 10_000)
            ?: throw AssertionError("diagnostics preview did not appear")
        val text = report.text
        openHome()
        return text
    }

    /** Scroll down until a text containing one of [parts] shows up (settled, like [scrollTo]). */
    private fun scrollToContaining(parts: List<String>): UiObject2? = scrollUntil {
        parts.firstNotNullOfOrNull { device.wait(Until.findObject(By.textContains(it)), 300) }
    }

    /** Any on-screen text containing one of [parts] (e.g. an error inside a longer message). */
    fun findContaining(parts: List<String>): UiObject2? =
        parts.firstNotNullOfOrNull { device.findObject(By.textContains(it)) }

    private fun anyOf(texts: List<String>): Pattern =
        Pattern.compile(texts.joinToString("|") { Pattern.quote(it) })

    // ---- timing & reporting --------------------------------------------------

    /** Poll [condition] until true; returns the elapsed ms, or fails with [what]. */
    fun waitUntil(what: String, timeoutMs: Long, condition: () -> Boolean): Long {
        val start = SystemClock.elapsedRealtime()
        while (true) {
            if (condition()) return SystemClock.elapsedRealtime() - start
            if (SystemClock.elapsedRealtime() - start > timeoutMs) {
                throw AssertionError("timed out after ${timeoutMs}ms: $what")
            }
            SystemClock.sleep(250)
        }
    }

    /** One result line in the `am instrument -r` output, picked up by run-reliability.sh. */
    fun report(line: String) {
        instrumentation.sendStatus(REPORT_CODE, Bundle().apply { putString("reliability", line) })
    }

    private const val REPORT_CODE = 2
}

/** UI strings, Chinese and English (the app follows the system language). */
object Text {
    val PERMISSIONS_TITLE = listOf("开启权限", "Enable permissions")
    val PERM_ACCESSIBILITY = listOf("读取聊天内容(无障碍)", "Read chat content (Accessibility)")
    val PERM_OVERLAY = listOf("显示悬浮球(悬浮窗)", "Show floating bubble (Overlay)")
    val ENABLE = listOf("去开启", "Enable")
    val TEST_CONNECTION = listOf("测试连接", "Test connection")
    val TESTING_CLOUD = listOf("测试中…", "Testing…")
    val TESTING_LOCAL = listOf("本地生成中…", "Generating locally…")
    val TEST_RESULT = listOf("测试结果", "Test result")
    val NETWORK_ERROR = listOf("网络连接失败,请检查网络", "Network connection failed — check your network")
    val CLOSE = listOf("关闭", "Close")
    val A11Y_LABEL = listOf("聊天助手 · 读屏助手", "Chat Assistant · Screen Reader")
    val ADVANCED_ENTRY = listOf("高级设置", "Advanced / Developer")
    val ADVANCED_MARKER = listOf("外观", "Appearance")
    val DIAG_EXPORT = listOf("导出诊断信息", "Export diagnostics")
}
