package com.octos.aichat.diagnostics

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val device =
        DeviceInfo("0.1.0", 1, "release", "HONOR", "ELP-AN00", "16", 36, "arm64-v8a", "zh-CN")

    private fun input(
        counts: Map<ErrorCode, Int> = emptyMap(),
        events: List<DiagEvent> = emptyList(),
        settings: List<Pair<String, String>> = listOf("inference" to "CLOUD", "ocrApps" to "2"),
    ) = ReportInput(
        generatedAtMs = 0,
        device = device,
        settings = settings,
        permissions = listOf("overlay" to true, "notifications" to false),
        counts = counts,
        events = events,
    )

    @Test fun emptyReportHasEverySection() {
        val text = DiagnosticsReport.render(input(), chinese = true, zone = utc)
        listOf(
            "== 聊天助手 诊断信息 ==",
            "生成时间  1970-01-01 00:00:00",
            "App       0.1.0 (1) release",
            "HONOR ELP-AN00 · Android 16 (API 36) · arm64-v8a · zh-CN",
            "inference CLOUD · ocrApps 2",
            "overlay 是 · notifications 否",
            "== 上次进程退出原因",
            "== 错误计数",
            "== 最近事件",
        )
            .forEach { assertTrue(it, it in text) }
        assertEquals("four empty sections", 4, Regex("""\(无\)""").findAll(text).count())
    }

    @Test fun countsSortedByFrequencyWithFixedDescriptions() {
        val text = DiagnosticsReport.render(
            input(
                counts = mapOf(
                    ErrorCode.BUB_501 to 1,
                    ErrorCode.AI_108 to 12,
                    ErrorCode.A11Y_304 to 3,
                ),
            ),
            chinese = false,
            zone = utc,
        )
        val lines = text.lines().filter {
            it.startsWith("AI-108") || it.startsWith("A11Y-304") || it.startsWith("BUB-501")
        }
        assertEquals(listOf("AI-108", "A11Y-304", "BUB-501"), lines.map { it.substringBefore(' ') })
        assertTrue(lines[0].endsWith("Network unavailable"))
    }

    @Test fun exitsNewestFirstAndRecentEventsListed() {
        val events = listOf(
            DiagEvent(1_000, ErrorCode.APP_902, exit = "CRASH", exitAt = 500),
            DiagEvent(2_000, ErrorCode.AI_108, cause = "UnknownHostException", ms = 31),
            DiagEvent(3_000, ErrorCode.APP_902, exit = "ANR", exitAt = 2_500),
        )
        val text = DiagnosticsReport.render(input(events = events), chinese = true, zone = utc)
        val exits = text.substringAfter("上次进程退出原因")
            .substringAfter("\n")
            .substringBefore("\n\n")
            .lines()
        assertEquals(listOf("1970-01-01 00:00:02  ANR", "1970-01-01 00:00:00  CRASH"), exits)
        assertTrue(
            "1970-01-01 00:00:02  AI-108 ERROR  cause=UnknownHostException ms=31  网络不可用" in text,
        )
    }

    @Test fun onlyTheNewestEventsAreListed() {
        val events = (1..250).map { DiagEvent(it * 1_000L, ErrorCode.AI_108, ms = it.toLong()) }
        val text = DiagnosticsReport.render(input(events = events), chinese = true, zone = utc)
        assertFalse("ms=50 " in text)
        assertTrue("ms=51 " in text && "ms=250 " in text)
    }

    @Test fun timingsShowSampleCountAndBucketedPercentiles() {
        // 10 taps: 8 within 2 s, 1 within 3 s, 1 within 5 s.
        val buckets = List(DurationBuckets.SIZE) { 0 }.toMutableList().apply {
            this[3] = 8
            this[4] = 1
            this[5] = 1
        }
        val text = DiagnosticsReport.render(
            input().copy(durations = mapOf(TimedOp.TAP_TO_RESULT to buckets)),
            chinese = true,
            zone = utc,
        )
        assertTrue(text, "点击到出结果  n=10  P50 ≤ 2s  P95 ≤ 5s" in text)
    }

    @Test fun nonIdentifierSettingValuesAreDropped() {
        val text = DiagnosticsReport.render(
            input(
                settings = listOf(
                    "inference" to "CLOUD",
                    "leak" to "https://my.host/v1",
                    "persona" to "温柔的人设",
                ),
            ),
            chinese = true,
            zone = utc,
        )
        assertTrue("inference CLOUD" in text)
        assertFalse("my.host" in text)
        assertFalse("温柔" in text)
    }
}
