package com.octos.aichat.ui

import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.BackendConfig
import com.octos.aichat.diagnostics.DeviceInfo
import com.octos.aichat.diagnostics.DiagnosticsRecorder
import com.octos.aichat.diagnostics.DiagnosticsReport
import com.octos.aichat.diagnostics.DiagnosticsStore
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.ReportInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End to end through the real pieces — settings summary, recorder, store, report —
 * with secrets and chat text planted everywhere they could leak from
 * (docs/observability-design.md §3, §9). None of the sentinels may reach the export.
 */
class DiagnosticsPrivacyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sentinels = listOf("SENTINEL", "sk-LEAK", "leak.example", "周末去爬山", "温柔体贴的人设")

    @Test fun noSecretOrChatTextReachesTheExport() {
        val settings = AppSettings(
            backend = AiBackend.DEEPSEEK,
            backendConfigs = mapOf(AiBackend.DEEPSEEK to BackendConfig("https://leak.example/v1", "SENTINEL-model", "sk-LEAK-123")),
            customPersona = "温柔体贴的人设 SENTINEL",
            targetPackages = "com.SENTINEL.app",
        )
        val recorder = DiagnosticsRecorder(DiagnosticsStore(File(tmp.root, "diag"))).apply { setEnabled(true) }
        recorder.record(ErrorCode.AI_109, cause = IllegalStateException("SENTINEL: 周末去爬山 sk-LEAK"))
        recorder.record(ErrorCode.A11Y_302, cause = RuntimeException("周末去爬山"), pkg = "com.tencent.mm")
        recorder.record(ErrorCode.OCR_402, pkg = "周末去爬山")
        recorder.recordCrash(Error("SENTINEL crash"), Thread("main"))

        val report = DiagnosticsReport.render(
            ReportInput(
                generatedAtMs = 0,
                device = DeviceInfo("0.1.0", 1, "release", "m", "d", "16", 36, "arm64-v8a", "zh-CN"),
                settings = diagnosticsSettings(settings),
                permissions = emptyList(),
                counts = recorder.countsSnapshot(),
                events = recorder.recent(1_000),
            ),
            chinese = true,
        )

        sentinels.forEach { assertFalse("leaked: $it\n$report", it in report) }
        // Still useful: the codes, classes and the allowed package are there.
        listOf("AI-109", "IllegalStateException", "A11Y-302", "pkg=com.tencent.mm", "APP-901", "apiKeySet true")
            .forEach { assertTrue(it, it in report) }
    }
}
