package com.octos.aichat.ai

import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.BackendConfig
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.InferenceMode
import com.octos.aichat.data.Speaker
import com.octos.aichat.data.memory.MemoryEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---- parseReplies -----------------------------------------------------

    @Test fun parseReplies_plainJsonArray() {
        assertEquals(listOf("好呀", "行"), service.parseReplies("""["好呀","行"]""", 3))
    }

    @Test fun parseReplies_fencedJsonWithChatter() {
        val raw = "这是回复:\n```json\n[\"a\", \"b\", \"c\"]\n```"
        assertEquals(listOf("a", "b", "c"), service.parseReplies(raw, 5))
    }

    @Test fun parseReplies_capsAtMax() {
        assertEquals(listOf("1", "2"), service.parseReplies("""["1","2","3","4"]""", 2))
    }

    @Test fun parseReplies_dropsBlankEntries() {
        assertEquals(listOf("x"), service.parseReplies("""["  ", "x", ""]""", 3))
    }

    @Test fun parseReplies_numberedLinesFallback() {
        val raw = "1. 第一条\n2、第二条\n- 第三条\n• 第四条"
        assertEquals(listOf("第一条", "第二条", "第三条", "第四条"), service.parseReplies(raw, 5))
    }

    @Test fun parseReplies_brokenJsonFallsBackToLines() {
        val raw = "[\"未闭合\n第二行"
        val out = service.parseReplies(raw, 5)
        assertTrue(out.isNotEmpty())
        assertTrue(out.contains("第二行"))
    }

    // ---- parseLearnResult -------------------------------------------------

    @Test fun parseLearn_fullObject() {
        val raw = """
            ```json
            {"additions":[{"category":"事实","content":"在北京做程序员","keywords":"北京, 程序员、工作","importance":8}],
             "updates":[{"id":12,"category":"偏好","content":"现在改喝拿铁","keywords":"拿铁","importance":5}],
             "deletions":[7]}
            ```
        """.trimIndent()
        val r = service.parseLearnResult(raw)
        assertNotNull(r)
        r!!
        assertEquals(1, r.additions.size)
        assertEquals("北京 程序员 工作", r.additions[0].keywords)
        assertEquals(8, r.additions[0].importance)
        assertEquals(12L, r.updates.single().id)
        assertEquals(listOf(7L), r.deletions)
    }

    @Test fun parseLearn_normalisesCategoryAndImportance() {
        val raw = """{"additions":[{"category":"瞎编","content":"c","importance":99},
                                   {"category":"风格","content":"d","importance":-3}]}"""
        val a = service.parseLearnResult(raw)!!.additions
        assertEquals("事实", a[0].category)
        assertEquals(10, a[0].importance)
        assertEquals("风格", a[1].category)
        assertEquals(1, a[1].importance)
    }

    @Test fun parseLearn_skipsInvalidRows() {
        val raw = """{"additions":[{"content":"  "}],
                      "updates":[{"content":"no id"},{"id":3,"content":""}],
                      "deletions":[-1, 4]}"""
        val r = service.parseLearnResult(raw)!!
        assertTrue(r.additions.isEmpty())
        assertTrue(r.updates.isEmpty())
        assertEquals(listOf(4L), r.deletions)
    }

    @Test fun parseLearn_capsKeywordsAtEight() {
        val raw = """{"additions":[{"content":"c","keywords":"a b c d e f g h i j"}]}"""
        assertEquals(8, service.parseLearnResult(raw)!!.additions[0].keywords.split(" ").size)
    }

    @Test fun parseLearn_garbageReturnsNull() {
        assertNull(service.parseLearnResult("抱歉,我无法完成"))
        assertNull(service.parseLearnResult("{not json}"))
    }

    // ---- generateReplies --------------------------------------------------

    private fun conv(vararg lines: Pair<Speaker, String>) =
        Conversation("pkg", lines.map { ChatMessage(it.first, it.second) })

    @Test fun generate_dropsMyTrailingTurnsAndInjectsMemory() = runTest {
        val fake = FakeClient("""["好的"]""")
        val svc = AiService { fake }
        val c = conv(Speaker.OTHER to "周末去爬山吗", Speaker.ME to "我想想", Speaker.ME to "最近有点累")

        val out = svc.generateReplies(cloudSettings, c, memoryBlock = "- 喜欢户外")

        assertEquals(listOf("好的"), out)
        assertTrue(fake.user!!.contains("对方: 周末去爬山吗"))
        assertFalse(fake.user!!.contains("最近有点累"))
        assertTrue(fake.system!!.contains("喜欢户外"))
    }

    @Test fun generate_clampsSuggestionCount() = runTest {
        val fake = FakeClient("""["1","2","3","4","5","6","7"]""")
        val svc = AiService { fake }
        val out = svc.generateReplies(cloudSettings.copy(suggestionCount = 99), conv(Speaker.OTHER to "hi"))
        assertEquals(5, out.size)
        assertTrue(fake.user!!.contains("给 5 条"))
    }

    @Test fun generate_requiresApiKeyInCloudMode() = runTest {
        val fake = FakeClient("[]")
        val svc = AiService { fake }
        try {
            svc.generateReplies(AppSettings(), conv(Speaker.OTHER to "hi"))
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
            AiService().generateReplies(local, conv(Speaker.OTHER to "hi"))
            fail("expected local-not-ready error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("本地模型未就绪"))
        }
    }

    // ---- learnMemory ------------------------------------------------------

    @Test fun learn_listsExistingMemoriesWithIds() = runTest {
        val fake = FakeClient("""{"additions":[],"updates":[],"deletions":[]}""")
        val svc = AiService { fake }
        val existing = listOf(MemoryEntity(id = 42, category = "事实", content = "养猫", updatedAt = 0))

        val r = svc.learnMemory(cloudSettings, existing, listOf("今天猫又拆家了"))

        assertNotNull(r)
        assertTrue(fake.user!!.contains("#42 【事实】养猫"))
        assertTrue(fake.user!!.contains("- 今天猫又拆家了"))
    }

    @Test fun learn_clientFailureReturnsNull() = runTest {
        val svc = AiService {
            object : AiClient {
                override suspend fun complete(settings: AppSettings, system: String, user: String): String =
                    throw AiException(AiException.Kind.SERVER, 500, "boom")
            }
        }
        assertNull(svc.learnMemory(cloudSettings, emptyList(), listOf("x")))
    }

    @Test fun learn_noRepliesSkipsCall() = runTest {
        val fake = FakeClient("{}")
        assertNull(AiService { fake }.learnMemory(cloudSettings, emptyList(), emptyList()))
        assertEquals(0, fake.calls)
    }

    // ---- reply time budget -------------------------------------------------

    private class SlowClient(private val ms: Long, private val answer: String = """["慢"]""") : AiClient {
        override suspend fun complete(settings: AppSettings, system: String, user: String): String {
            delay(ms); return answer
        }
    }

    @Test fun cloudReplyOverBudgetIsTimeout() = runBlocking {
        val svc = AiService(clientFor = { SlowClient(5_000) }, replyTimeoutMs = 50)
        val started = System.nanoTime()
        try {
            svc.generateReplies(cloudSettings, conv(Speaker.OTHER to "hi"))
            fail("expected timeout")
        } catch (e: AiException) {
            assertEquals(AiException.Kind.TIMEOUT, e.kind)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test fun localReplyIsNotCutByCloudBudget() = runBlocking {
        val local = cloudSettings.copy(inferenceMode = InferenceMode.LOCAL)
        val svc = AiService(clientFor = { SlowClient(150) }, replyTimeoutMs = 50)
        assertEquals(listOf("慢"), svc.generateReplies(local, conv(Speaker.OTHER to "hi")))
    }
}
