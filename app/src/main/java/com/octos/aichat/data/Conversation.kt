package com.octos.aichat.data

/** Who authored a line in the on-screen conversation. */
enum class Speaker { ME, OTHER }

data class ChatMessage(val speaker: Speaker, val text: String)

data class Conversation(val packageName: String, val messages: List<ChatMessage>) {
    val isEmpty: Boolean get() = messages.isEmpty()

    /** The most recent message from the other party, if any. */
    fun lastFromOther(): String? = messages.lastOrNull { it.speaker == Speaker.OTHER }?.text

    /** My most recent message on screen, if any. */
    fun lastFromMe(): String? = messages.lastOrNull { it.speaker == Speaker.ME }?.text

    /**
     * Drop trailing messages I authored. When generating suggestions we want the AI
     * to respond to the other party's latest line, not continue my own last
     * message — so my trailing turns are removed from the model input.
     */
    fun withoutTrailingMine(): Conversation {
        var end = messages.size
        while (end > 0 && messages[end - 1].speaker == Speaker.ME) end--
        return if (end ==
            messages.size
        ) {
            this
        } else {
            copy(messages = messages.subList(0, end).toList())
        }
    }

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
