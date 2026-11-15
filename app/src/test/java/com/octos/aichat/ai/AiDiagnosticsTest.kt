package com.octos.aichat.ai

import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.BackendConfig
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.DiagnosticsRecorder
import com.octos.aichat.diagnostics.DiagnosticsStore
import com.octos.aichat.diagnostics.ErrorCode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiDiagnosticsTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var recorder: DiagnosticsRecorder

    @Before fun setUp() {
        recorder =
            DiagnosticsRecorder(DiagnosticsStore(File(tmp.root, "diag"))).apply { setEnabled(true) }
        DiagLog.install(recorder)
    }

    @After fun tearDown() = DiagLog.setEnabled(false)

    @Test fun everyKindHasItsOwnCode() {
        val codes = AiException.Kind.entries.associateWith { AiException(it).diagnosticsCode() }
        assertEquals(ErrorCode.LLM_201, codes[AiException.Kind.LOCAL_TIMEOUT])
        assertEquals(ErrorCode.AI_108, codes[AiException.Kind.NETWORK])
        assertEquals("one code per kind", codes.size, codes.values.toSet().size)
    }

    @Test fun nonAiFailuresAreOther() {
        assertEquals(ErrorCode.AI_110, IllegalArgumentException("尚未填写 API Key").diagnosticsCode())
    }

    @Test fun recordKeepsHttpStatusButNotTheBody() {
        DiagLog.recordAiFailure(AiException.fromHttp(402, """{"error":"SENTINEL body"}"""))
        val e = recorder.recent(10).single()
        assertEquals(ErrorCode.AI_102, e.code)
        assertEquals(402, e.http)
        assertEquals("AiException", e.cause)
    }

    private val settings = AppSettings(
        backend = AiBackend.DEEPSEEK,
        backendConfigs = mapOf(AiBackend.DEEPSEEK to BackendConfig("https://x", "m", "sk-test")),
    )

    @Test fun learningFailuresAreRecorded() = runBlocking {
        val failing = AiService {
            object : AiClient {
                override suspend fun complete(
                    settings: AppSettings,
                    system: String,
                    user: String,
                ): String = throw AiException(AiException.Kind.SERVER, 503)
            }
        }
        assertNull(failing.learnMemory(settings, emptyList(), listOf("x")))

        val garbage = AiService {
            object : AiClient {
                override suspend fun complete(settings: AppSettings, system: String, user: String) =
                    "抱歉,我无法完成"
            }
        }
        assertNull(garbage.learnMemory(settings, emptyList(), listOf("x")))

        assertEquals(
            listOf(ErrorCode.AI_106, ErrorCode.MEM_602),
            recorder.recent(10).map {
                it.code
            },
        )
    }
}
