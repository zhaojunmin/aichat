package com.octos.aichat.data.memory

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Counts snapshot from the reply log. */
data class ReplyCounts(val total: Int, val learned: Int)

/** The un-learned replies plus the current total line count. */
data class PendingReplies(val replies: List<String>, val total: Int)

/** Where the learner reads un-learned replies and records how far it got. */
interface ReplyLedger {
    suspend fun pending(): PendingReplies

    /** Record that the first [total] lines are learned. Called inside the learning transaction. */
    suspend fun markLearned(total: Int)
}

/**
 * The raw record of every reply I authored — one line per reply as
 * "epochMillis\ttext" in `my_replies.txt`, plus a bounded dedup set and the
 * "how many have been learned" offset. Owns all file + offset access; the memory
 * learner reads un-learned lines from here. Single responsibility: the reply log.
 *
 * The offset is stored in the memory database ([LearningStateDao]) so the learner
 * can advance it in the same transaction as its memory writes. Older installs kept
 * it in SharedPreferences; it is moved over on first read.
 */
class ReplyLog(context: Context, private val state: LearningStateDao) : ReplyLedger {

    private val file = File(context.filesDir, "my_replies.txt")
    private val legacyPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()

    // Bounded set of recently logged replies so the accessibility reader re-scanning
    // the same on-screen message doesn't record duplicates.
    private val recent = LinkedHashSet<String>()

    /** Read existing lines to prime the dedup set; returns current counts. */
    suspend fun primeRecent(): ReplyCounts = lock.withLock {
        val lines = readLines()
        lines.takeLast(RECENT_MEMORY).forEach { recent.add(textOf(it)) }
        ReplyCounts(total = lines.size, learned = learnedOffset().coerceIn(0, lines.size))
    }

    /** Append one reply (normalised + deduped). @return true if it was newly added. */
    suspend fun append(raw: String): Boolean = lock.withLock {
        val text = normalize(raw)
        if (text.isEmpty() || !recent.add(text)) return@withLock false
        if (recent.size > RECENT_MEMORY) recent.iterator().let { it.next(); it.remove() }
        runCatching { file.appendText("${System.currentTimeMillis()}\t$text\n") }
        true
    }

    /** Replies not yet learned (from the stored offset), with the total line count. */
    override suspend fun pending(): PendingReplies = lock.withLock {
        val lines = readLines()
        val from = learnedOffset().coerceIn(0, lines.size)
        PendingReplies(lines.subList(from, lines.size).map { textOf(it) }, lines.size)
    }

    /** Mark the first [total] lines as learned. */
    override suspend fun markLearned(total: Int) = state.set(LearningState(learnedReplies = total))

    suspend fun clear() = lock.withLock {
        runCatching { if (file.exists()) file.delete() }
        state.set(LearningState(learnedReplies = 0))
        recent.clear()
    }

    /** The stored offset; on the first read after upgrading, moved over from prefs. */
    private suspend fun learnedOffset(): Int =
        state.learnedReplies() ?: legacyPrefs.getInt(KEY_LEARNED, 0).also { legacy ->
            state.set(LearningState(learnedReplies = legacy))
            legacyPrefs.edit().remove(KEY_LEARNED).apply()
        }

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    /** One reply per line as "epochMillis\ttext"; grab the text half. */
    private fun textOf(line: String): String = line.substringAfter('\t', line).trim()

    private fun normalize(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")

    private companion object {
        const val PREFS = "ai_chat_memory"
        const val KEY_LEARNED = "learned_reply_count"
        const val RECENT_MEMORY = 300
    }
}
