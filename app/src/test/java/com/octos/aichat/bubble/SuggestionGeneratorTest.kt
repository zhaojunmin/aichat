package com.octos.aichat.bubble

import com.octos.aichat.ai.AiClient
import com.octos.aichat.ai.AiException
import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.BackendConfig
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.InferenceMode
import com.octos.aichat.data.Speaker
import com.octos.aichat.data.memory.MemoryEntity
import com.octos.aichat.data.memory.MemoryStats
import com.octos.aichat.data.memory.MemoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric because [SuggestionGenerator.suggestFor] traces through android.os.Trace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SuggestionGeneratorTest {

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

    private val generator = SuggestionGenerator()

    // ---- parseSuggestions -----------------------------------------------------

    @Test fun parseSuggestions_plainJsonArray() {
        assertEquals(listOf("好呀", "行"), generator.parseSuggestions("""["好呀","行"]""", 3))
    }

    @Test fun parseSuggestions_fencedJsonWithChatter() {
        val raw = "这是回复:\n```json\n[\"a\", \"b\", \"c\"]\n```"
        assertEquals(listOf("a", "b", "c"), generator.parseSuggestions(raw, 5))
    }

    @Test fun parseSuggestions_capsAtMax() {
        assertEquals(listOf("1", "2"), generator.parseSuggestions("""["1","2","3","4"]""", 2))
    }

    @Test fun parseSuggestions_dropsBlankEntries() {
        assertEquals(listOf("x"), generator.parseSuggestions("""["  ", "x", ""]""", 3))
    }

    @Test fun parseSuggestions_numberedLinesFallback() {
        val raw = "1. 第一条\n2、第二条\n- 第三条\n• 第四条"
        assertEquals(listOf("第一条", "第二条", "第三条", "第四条"), generator.parseSuggestions(raw, 5))
    }

    @Test fun parseSuggestions_brokenJsonFallsBackToLines() {
        val raw = "[\"未闭合\n第二行"
        val out = generator.parseSuggestions(raw, 5)
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
            assertEquals(
                "for input <$raw>",
                emptyList<String>(),
                generator.parseSuggestions(raw, 3),
            )
        }
    }

    @Test fun parseSuggestions_arrayInsideObjectOrProse() {
        assertEquals(listOf("a", "b"), generator.parseSuggestions("""{"replies":["a","b"]}""", 3))
        assertEquals(listOf("嗯嗯"), generator.parseSuggestions("好的,给你:[\"嗯嗯\"] 希望有用", 3))
    }

    @Test fun parseSuggestions_nonStringItemsAreStringified() {
        assertEquals(
            listOf("1", "true", "ok"),
            generator.parseSuggestions("""[1, true, "ok"]""", 5),
        )
    }

    @Test fun parseSuggestions_plainSentenceIsOneSuggestion() {
        assertEquals(listOf("今晚可以"), generator.parseSuggestions("今晚可以", 3))
    }

    @Test fun parseSuggestions_hugeAnswerStillCapped() {
        val raw = (1..500).joinToString("\n") { "$it. 第 $it 条" }
        val out = generator.parseSuggestions(raw, 3)
        assertEquals(listOf("第 1 条", "第 2 条", "第 3 条"), out)
    }

    // ---- generate -----------------------------------------------------------

    private fun conv(vararg lines: Pair<Speaker, String>) =
        Conversation("pkg", lines.map { ChatMessage(it.first, it.second) })

    @Test fun generate_dropsMyTrailingTurnsAndInjectsMemory() = runTest {
        val fake = FakeClient("""["好的"]""")
        val svc = SuggestionGenerator { fake }
        val c = conv(Speaker.OTHER to "周末去爬山吗", Speaker.ME to "我想想", Speaker.ME to "最近有点累")

        val out = svc.generate(cloudSettings, c, memoryBlock = "- 喜欢户外")

        assertEquals(listOf("好的"), out)
        assertTrue(fake.user!!.contains("对方: 周末去爬山吗"))
        assertFalse(fake.user!!.contains("最近有点累"))
        assertTrue(fake.system!!.contains("喜欢户外"))
    }

    @Test fun generate_clampsSuggestionCount() = runTest {
        val fake = FakeClient("""["1","2","3","4","5","6","7"]""")
        val svc = SuggestionGenerator { fake }
        val out = svc.generate(
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
                SuggestionGenerator {
                    FakeClient(answer)
                }.generate(cloudSettings, conv(Speaker.OTHER to "hi"))
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
        val svc = SuggestionGenerator {
            object : AiClient {
                override suspend fun complete(
                    settings: AppSettings,
                    system: String,
                    user: String,
                ): String = throw AiException(AiException.Kind.INSUFFICIENT_BALANCE, 402)
            }
        }
        try {
            svc.generate(cloudSettings, conv(Speaker.OTHER to "hi"))
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.INSUFFICIENT_BALANCE, e.kind)
        }
    }

    @Test fun generate_requiresApiKeyInCloudMode() = runTest {
        val fake = FakeClient("[]")
        val svc = SuggestionGenerator { fake }
        try {
            svc.generate(AppSettings(), conv(Speaker.OTHER to "hi"))
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
            SuggestionGenerator().generate(local, conv(Speaker.OTHER to "hi"))
            fail("expected a local-model error")
        } catch (e: AiException) {
            // No native engine on the JVM: the local client says so, typed, and the cloud
            // client is never tried.
            assertEquals(AiException.Kind.LOCAL_UNSUPPORTED, e.kind)
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
        val svc = SuggestionGenerator(clientFor = { SlowClient(5_000) }, suggestionTimeoutMs = 50)
        val started = System.nanoTime()
        try {
            svc.generate(cloudSettings, conv(Speaker.OTHER to "hi"))
            fail("expected timeout")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.TIMEOUT, e.kind)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test fun localSuggestionsAreNotCutByCloudBudget() = runBlocking {
        val local = cloudSettings.copy(inferenceMode = InferenceMode.LOCAL)
        val svc = SuggestionGenerator(clientFor = { SlowClient(150) }, suggestionTimeoutMs = 50)
        assertEquals(listOf("慢"), svc.generate(local, conv(Speaker.OTHER to "hi")))
    }

    // ---- suggestFor (one tap) -------------------------------------------------

    /** Records the calls the tap flow makes; everything else is unused here. */
    private class FakeMemory(private val block: String) : MemoryStore {
        val calls = mutableListOf<String>()
        override val memories = MutableStateFlow(emptyList<MemoryEntity>())
        override val deleted = MutableStateFlow(emptyList<MemoryEntity>())
        override val stats = MutableStateFlow(MemoryStats())
        override fun recordMyMessages(conv: Conversation) {
            calls += "record"
        }
        override fun memoryBlock(conv: Conversation?): String {
            calls += "block"
            return block
        }
        override fun recordReply(text: String) = Unit
        override fun learnIfDue() = Unit
        override suspend fun learnNow() = Unit
        override suspend fun deleteMemory(id: Long) = Unit
        override suspend fun restoreMemory(id: Long) = Unit
        override suspend fun purgeMemory(id: Long) = Unit
        override suspend fun editMemory(id: Long, content: String) = Unit
        override suspend fun clearAll() = Unit
    }

    @Test fun suggestFor_recordsMyMessagesThenPromptsWithWhatMemoryKnows() = runTest {
        val fake = FakeClient("""["好呀"]""")
        val memory = FakeMemory("【偏好】喜欢户外")

        val out = SuggestionGenerator { fake }.suggestFor(
            cloudSettings,
            conv(Speaker.OTHER to "周末去哪"),
            memory,
        )

        assertEquals(listOf("好呀"), out)
        assertEquals(listOf("record", "block"), memory.calls)
        assertTrue(fake.system!!.contains("喜欢户外"))
    }

    @Test fun suggestFor_failureIsRethrown_afterMyMessagesWereRecorded() = runTest {
        val memory = FakeMemory("")
        val failing = SuggestionGenerator {
            object : AiClient {
                override suspend fun complete(settings: AppSettings, system: String, user: String) =
                    throw AiException(AiException.Kind.SERVER, 503)
            }
        }
        try {
            failing.suggestFor(cloudSettings, conv(Speaker.OTHER to "hi"), memory)
            fail("expected the client's failure")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.SERVER, e.kind)
        }
        assertEquals(
            "my messages are kept even when generation fails",
            "record",
            memory.calls.first(),
        )
    }
}
