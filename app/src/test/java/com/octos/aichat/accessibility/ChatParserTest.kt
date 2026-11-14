package com.octos.aichat.accessibility

import com.octos.aichat.data.Conversation
import com.octos.aichat.data.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Golden tests: every `dumps/<pkg>/<case>.xml` is parsed and compared with its
 * hand-written `<case>.expected.txt`. Screen size = the dump's root bounds.
 */
class ChatParserGoldenTest {

    private fun render(c: Conversation) =
        c.messages.joinToString("\n") { (if (it.speaker == Speaker.ME) "我" else "对方") + ": " + it.text }

    @Test fun allRecordedDumps() {
        val cases = UiDumps.dir.walkTopDown().filter { it.extension == "xml" }.toList()
        assertTrue("no golden dumps found under ${UiDumps.dir.absolutePath}", cases.isNotEmpty())
        val failures = cases.mapNotNull { xml ->
            val expectedFile = File(xml.parentFile, xml.nameWithoutExtension + ".expected.txt")
            val expected = expectedFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }.joinToString("\n")
            val root = UiDumps.load(xml)
            val pkg = xml.parentFile.name
            val actual = render(ChatParser.parse(root, root.bounds.width, root.bounds.height, pkg))
            if (actual == expected) null
            else "${xml.relativeTo(UiDumps.dir)}\n--- expected\n$expected\n--- actual\n$actual"
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
}

/** Rule-level behaviour on small hand-built trees (screen 1000×2000). */
class ChatParserTest {

    private val w = 1000
    private val h = 2000

    private fun node(
        id: String? = null,
        text: String? = null,
        b: Bounds = Bounds(0, 0, w, h),
        vararg kids: UiNode,
    ) = UiNode(id?.let { "app:id/$it" }, null, text, null, b, kids.toList())

    private fun screen(vararg kids: UiNode) = node(b = Bounds(0, 0, w, h), kids = *kids)

    private fun lines(c: Conversation) =
        c.messages.map { (if (it.speaker == Speaker.ME) "我" else "对方") + ": " + it.text }

    @Test fun selfOthersIdsDecideSpeaker_hertz() {
        // Speaker comes from the id even when the position would say otherwise.
        val root = screen(
            node("tv_chat_content_others", "你好", Bounds(600, 300, 900, 360)),
            node("tv_chat_content_self", "嗨", Bounds(100, 500, 300, 560)),
        )
        assertEquals(listOf("对方: 你好", "我: 嗨"), lines(ChatParser.parse(root, w, h, "com.immomo.vchat")))
    }

    @Test fun avatarRow_soul() {
        fun row(top: Int, avatarLeft: Int, msg: String, msgLeft: Int) = node(
            "item_root", null, Bounds(0, top, w, top + 200),
            node("chat_avatar", null, Bounds(avatarLeft, top, avatarLeft + 150, top + 150)),
            // A long bubble spanning most of the width: position alone would be ambiguous.
            node("content_text", msg, Bounds(msgLeft, top + 20, msgLeft + 700, top + 80)),
        )
        val root = screen(row(300, 20, "对方长消息", 200), row(600, 830, "我的长消息", 100))
        assertEquals(listOf("对方: 对方长消息", "我: 我的长消息"), lines(ChatParser.parse(root, w, h, "cn.soulapp.android")))
    }

    @Test fun ruleMatchingNothingFallsBackToGeneric_qqIdChanged() {
        // QQ's obfuscated message id changed: no node has "mjn", so position-based parsing kicks in.
        val root = screen(
            node("newid", "在吗", Bounds(30, 400, 400, 460)),
            node("newid", "在的", Bounds(600, 600, 980, 660)),
        )
        assertEquals(listOf("对方: 在吗", "我: 在的"), lines(ChatParser.parse(root, w, h, "com.tencent.mobileqq")))
    }

    @Test fun generic_dropsChromeNoiseTimestampsAndDuplicates() {
        val root = screen(
            node(text = "张三", b = Bounds(400, 50, 600, 120)),           // title bar: outside the band
            node(text = "12:30", b = Bounds(450, 300, 550, 340)),         // timestamp separator
            node(text = "吃了吗", b = Bounds(30, 400, 400, 460)),
            node(text = "吃了吗", b = Bounds(30, 470, 400, 530)),        // re-layout duplicate
            node(text = "对方正在输入...", b = Bounds(30, 600, 400, 660)),
            node(text = "某人撤回了一条消息", b = Bounds(300, 700, 700, 740)),
            node(text = "吃过了", b = Bounds(600, 800, 980, 860)),
            node(text = "发送", b = Bounds(850, 1900, 980, 1980)),        // input bar
        )
        assertEquals(listOf("对方: 吃了吗", "我: 吃过了"), lines(ChatParser.parse(root, w, h, "unknown.app")))
    }

    @Test fun generic_ordersTopToBottom() {
        val root = screen(
            node(text = "第二", b = Bounds(600, 800, 980, 860)),
            node(text = "第一", b = Bounds(30, 400, 400, 460)),
        )
        assertEquals(listOf("对方: 第一", "我: 第二"), lines(ChatParser.parse(root, w, h, "unknown.app")))
    }

    @Test fun contentDescriptionUsedWhenNoText() {
        val root = screen(UiNode(null, null, null, "图片说明", Bounds(30, 400, 400, 460)))
        assertEquals(listOf("对方: 图片说明"), lines(ChatParser.parse(root, w, h, "unknown.app")))
    }

    @Test fun emptyInputs() {
        assertTrue(ChatParser.parse(null as UiNode?, w, h, "x").isEmpty)
        assertTrue(ChatParser.parse(screen(), 0, h, "x").isEmpty)
    }
}
