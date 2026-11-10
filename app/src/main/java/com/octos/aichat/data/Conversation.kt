package com.octos.aichat.data

/** Who authored a line in the on-screen conversation. */
enum class Speaker { ME, OTHER }

data class ChatMessage(
    val speaker: Speaker,
    val text: String,
)

data class Conversation(
    val packageName: String,
    val messages: List<ChatMessage>,
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    /** The most recent message from the other party, if any. */
    fun lastFromOther(): String? =
        messages.lastOrNull { it.speaker == Speaker.OTHER }?.text

    /** Render as plain transcript lines for the LLM prompt. */
    fun toTranscript(maxLines: Int = 30): String {
        val tail = messages.takeLast(maxLines)
        return tail.joinToString("\n") { m ->
            val who = if (m.speaker == Speaker.ME) "我" else "对方"
            "$who: ${m.text}"
        }
    }

    companion object {
        fun empty() = Conversation("", emptyList())
    }
}
