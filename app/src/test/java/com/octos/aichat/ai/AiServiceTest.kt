package com.octos.aichat.ai

import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.BackendConfig
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.InferenceMode
import com.octos.aichat.data.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiServiceTest {

    /** Records what it was asked and returns a canned answer. */
    private class FakeClient(private val answer: String) : AiClient {
        var system: String? = null
        var user: String? = null
        var calls = 0
        override suspend fun complete(settings: AppSettings, system: String, user: String): String {
            calls++
            this.system = system
            this.user = user
            return answer
        }
    }

    private val cloudSettings = AppSettings(
        backend = AiBackend.DEEPSEEK,
        backendConfigs = mapOf(AiBackend.DEEPSEEK to BackendConfig("https://x", "m", "sk-test")),
    )

    private val service = AiService()

    // ---- parseSuggestions -----------------------------------------------------

    @Test fun parseSuggestions_plainJsonArray() {
        assertEquals(listOf("好呀", "行"), service.parseSuggestions("""["好呀","行"]""", 3))
    }

    @Test fun parseSuggestions_fencedJsonWithChatter() {
        val raw = "这是回复:\n```json\n[\"a\", \"b\", \"c\"]\n```"
        assertEquals(listOf("a", "b", "c"), service.parseSuggestions(raw, 5))
    }

    @Test fun parseSuggestions_capsAtMax() {
        assertEquals(listOf("1", "2"), service.parseSuggestions("""["1","2","3","4"]""", 2))
    }

    @Test fun parseSuggestions_dropsBlankEntries() {
        assertEquals(listOf("x"), service.parseSuggestions("""["  ", "x", ""]""", 3))
    }

    @Test fun parseSuggestions_numberedLinesFallback() {
        val raw = "1. 第一条\n2、第二条\n- 第三条\n• 第四条"
        assertEquals(listOf("第一条", "第二条", "第三条", "第四条"), service.parseSuggestions(raw, 5))
    }

    @Test fun parseSuggestions_brokenJsonFallsBackToLines() {
        val raw = "[\"未闭合\n第二行"
        val out = service.parseSuggestions(raw, 5)
        assertTrue(out.isNotEmpty())
        assertTrue(out.contains("第二行"))
    }

    // R3: content-free answers yield no candidates (never a blank or literal "[]" one).
    @Test fun parseSuggestions_contentFreeAnswersAreEmpty() {
        for (raw in listOf(
            "",
            "   \n\t",
            "[]",
            "```json\n[]\n```",
            "```",
            """["", "  "]""",
            "-\n•\n1.",
        )) {
            assertEquals("for input <$raw>", emptyList<String>(), service.parseSuggestions(raw, 3))
        }
    }

    @Test fun parseSuggestions_arrayInsideObjectOrProse() {
        assertEquals(listOf("a", "b"), service.parseSuggestions("""{"replies":["a","b"]}""", 3))
        assertEquals(listOf("嗯嗯"), service.parseSuggestions("好的,给你:[\"嗯嗯\"] 希望有用", 3))
    }

    @Test fun parseSuggestions_nonStringItemsAreStringified() {
        assertEquals(listOf("1", "true", "ok"), service.parseSuggestions("""[1, true, "ok"]""", 5))
    }

    @Test fun parseSuggestions_plainSentenceIsOneSuggestion() {
        assertEquals(listOf("今晚可以"), service.parseSuggestions("今晚可以", 3))
    }

    @Test fun parseSuggestions_hugeAnswerStillCapped() {
        val raw = (1..500).joinToString("\n") { "$it. 第 $it 条" }
        val out = service.parseSuggestions(raw, 3)
        assertEquals(listOf("第 1 条", "第 2 条", "第 3 条"), out)
    }

    // ---- generateSuggestions --------------------------------------------------

    private fun conv(vararg lines: Pair<Speaker, String>) =
        Conversation("pkg", lines.map { ChatMessage(it.first, it.second) })

    @Test fun generate_dropsMyTrailingTurnsAndInjectsMemory() = runTest {
        val fake = FakeClient("""["好的"]""")
        val svc = AiService { fake }
        val c = conv(Speaker.OTHER to "周末去爬山吗", Speaker.ME to "我想想", Speaker.ME to "最近有点累")

        val out = svc.generateSuggestions(cloudSettings, c, memoryBlock = "- 喜欢户外")

        assertEquals(listOf("好的"), out)
        assertTrue(fake.user!!.contains("对方: 周末去爬山吗"))
        assertFalse(fake.user!!.contains("最近有点累"))
        assertTrue(fake.system!!.contains("喜欢户外"))
    }

    @Test fun generate_clampsSuggestionCount() = runTest {
        val fake = FakeClient("""["1","2","3","4","5","6","7"]""")
        val svc = AiService { fake }
        val out = svc.generateSuggestions(
            cloudSettings.copy(suggestionCount = 99),
            conv(Speaker.OTHER to "hi"),
        )
        assertEquals(5, out.size)
        assertTrue(fake.user!!.contains("给 5 条"))
    }

    /** R3: an empty model answer is a clear BAD_RESPONSE error, not a blank suggestion. */
    @Test fun generate_emptyAnswerIsBadResponse() = runTest {
        for (answer in listOf("", "[]", "```json\n[]\n```")) {
            try {
                AiService {
                    FakeClient(answer)
                }.generateSuggestions(cloudSettings, conv(Speaker.OTHER to "hi"))
                fail("expected BAD_RESPONSE for <$answer>")
            } catch (e: AiException) {
                assertEquals(AiException.Kind.BAD_RESPONSE, e.kind)
            }
        }
    }

    /**
     * R4: a cloud failure reaches the caller with its kind intact (the UI picks the hint from it).
     */
    @Test fun generate_clientErrorKindIsPreserved() = runTest {
        val svc = AiService {
            object : AiClient {
                override suspend fun complete(
                    settings: AppSettings,
                    system: String,
                    user: String,
                ): String = throw AiException(AiException.Kind.INSUFFICIENT_BALANCE, 402)
            }
        }
        try {
            svc.generateSuggestions(cloudSettings, conv(Speaker.OTHER to "hi"))
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.INSUFFICIENT_BALANCE, e.kind)
        }
    }

    @Test fun generate_requiresApiKeyInCloudMode() = runTest {
        val fake = FakeClient("[]")
        val svc = AiService { fake }
        try {
            svc.generateSuggestions(AppSettings(), conv(Speaker.OTHER to "hi"))
            fail("expected missing-key error")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, fake.calls)
    }

    /**
     * Privacy invariant: in local mode, if the on-device model isn't loaded (it never
     * is on the JVM), the default client selection must fail — never fall back to cloud.
     */
    @Test fun localMode_neverFallsBackToCloud() = runTest {
        val local = cloudSettings.copy(inferenceMode = InferenceMode.LOCAL)
        try {
            AiService().generateSuggestions(local, conv(Speaker.OTHER to "hi"))
            fail("expected local-not-ready error")
        } catch (e: AiException) {
            // A typed failure, so diagnostics file it as LLM-205 rather than "other".
            assertEquals(AiException.Kind.LOCAL_NOT_READY, e.kind)
        }
    }

    // ---- suggestion time budget --------------------------------------------

    private class SlowClient(private val ms: Long, private val answer: String = """["慢"]""") :
        AiClient {
        override suspend fun complete(settings: AppSettings, system: String, user: String): String {
            delay(ms)
            return answer
        }
    }

    @Test fun cloudSuggestionsOverBudgetAreTimeout() = runBlocking {
        val svc = AiService(clientFor = { SlowClient(5_000) }, suggestionTimeoutMs = 50)
        val started = System.nanoTime()
        try {
            svc.generateSuggestions(cloudSettings, conv(Speaker.OTHER to "hi"))
            fail("expected timeout")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.TIMEOUT, e.kind)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test fun localSuggestionsAreNotCutByCloudBudget() = runBlocking {
        val local = cloudSettings.copy(inferenceMode = InferenceMode.LOCAL)
        val svc = AiService(clientFor = { SlowClient(150) }, suggestionTimeoutMs = 50)
        assertEquals(listOf("慢"), svc.generateSuggestions(local, conv(Speaker.OTHER to "hi")))
    }
}
