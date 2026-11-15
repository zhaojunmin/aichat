package com.octos.aichat.data.memory

import com.octos.aichat.ai.AiClient
import com.octos.aichat.ai.AiException
import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.BackendConfig
import com.octos.aichat.data.InferenceMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the learner asks the model, and how it reads the answer back into memory changes. */
class MemoryLearnerTest {

    /** Records what it was asked and returns a canned answer. */
    private class FakeClient(private val answer: String) : AiClient {
        var user: String? = null
        var calls = 0
        override suspend fun complete(settings: AppSettings, system: String, user: String): String {
            calls++
            this.user = user
            return answer
        }
    }

    private val cloudSettings = AppSettings(
        backend = AiBackend.DEEPSEEK,
        backendConfigs = mapOf(AiBackend.DEEPSEEK to BackendConfig("https://x", "m", "sk-test")),
    )

    private val learner = MemoryLearner()

    // ---- parseLearnResult -------------------------------------------------

    @Test fun parseLearn_fullObject() {
        val raw = """
            ```json
            {"additions":[{"category":"事实","content":"在北京做程序员",
                           "keywords":"北京, 程序员、工作","importance":8}],
             "updates":[{"id":12,"category":"偏好","content":"现在改喝拿铁",
                         "keywords":"拿铁","importance":5}],
             "deletions":[7]}
            ```
        """.trimIndent()
        val r = learner.parseLearnResult(raw)
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
        val a = learner.parseLearnResult(raw)!!.additions
        assertEquals("事实", a[0].category)
        assertEquals(10, a[0].importance)
        assertEquals("风格", a[1].category)
        assertEquals(1, a[1].importance)
    }

    @Test fun parseLearn_skipsInvalidRows() {
        val raw = """{"additions":[{"content":"  "}],
                      "updates":[{"content":"no id"},{"id":3,"content":""}],
                      "deletions":[-1, 4]}"""
        val r = learner.parseLearnResult(raw)!!
        assertTrue(r.additions.isEmpty())
        assertTrue(r.updates.isEmpty())
        assertEquals(listOf(4L), r.deletions)
    }

    @Test fun parseLearn_capsKeywordsAtEight() {
        val raw = """{"additions":[{"content":"c","keywords":"a b c d e f g h i j"}]}"""
        assertEquals(8, learner.parseLearnResult(raw)!!.additions[0].keywords.split(" ").size)
    }

    @Test fun parseLearn_garbageReturnsNull() {
        assertNull(learner.parseLearnResult("抱歉,我无法完成"))
        assertNull(learner.parseLearnResult("{not json}"))
    }

    // ---- learn ------------------------------------------------------------

    @Test fun learn_listsExistingMemoriesWithIds() = runTest {
        val fake = FakeClient("""{"additions":[],"updates":[],"deletions":[]}""")
        val learnerUnderTest = MemoryLearner(clientFor = { fake })
        val existing = listOf(MemoryEntity(id = 42, category = "事实", content = "养猫", updatedAt = 0))

        val r = learnerUnderTest.learn(cloudSettings, existing, listOf("今天猫又拆家了"))

        assertNotNull(r)
        assertTrue(fake.user!!.contains("#42 【事实】养猫"))
        assertTrue(fake.user!!.contains("- 今天猫又拆家了"))
    }

    @Test fun learn_clientFailureReturnsNull() = runTest {
        val learnerUnderTest = MemoryLearner(clientFor = {
            object : AiClient {
                override suspend fun complete(
                    settings: AppSettings,
                    system: String,
                    user: String,
                ): String = throw AiException(AiException.Kind.SERVER, 500, "boom")
            }
        })
        assertNull(learnerUnderTest.learn(cloudSettings, emptyList(), listOf("x")))
    }

    @Test fun learn_noRepliesSkipsCall() = runTest {
        val fake = FakeClient("{}")
        assertNull(
            MemoryLearner(clientFor = {
                fake
            }).learn(cloudSettings, emptyList(), emptyList()),
        )
        assertEquals(0, fake.calls)
    }

    /** Learning waits for the on-device model rather than failing (and recording) every time. */
    @Test fun learn_localModelNotLoadedSkipsQuietly() = runTest {
        val fake = FakeClient("{}")
        val local = cloudSettings.copy(inferenceMode = InferenceMode.LOCAL)
        assertNull(
            MemoryLearner(clientFor = {
                fake
            }, localReady = { false }).learn(local, emptyList(), listOf("x")),
        )
        assertEquals(0, fake.calls)
    }
}
