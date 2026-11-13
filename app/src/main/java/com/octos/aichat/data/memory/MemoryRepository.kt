package com.octos.aichat.data.memory

import android.content.Context
import com.octos.aichat.ai.AiRepository
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Snapshot of memory state for the settings UI. */
data class MemoryStats(
    val totalReplies: Int = 0,
    val learnedReplies: Int = 0,
    val learning: Boolean = false,
) {
    val pending: Int get() = (totalReplies - learnedReplies).coerceAtLeast(0)
}

/**
 * Global memory: every reply I make is appended to a plain-text log; once enough
 * un-learned lines pile up, the LLM distills them into a small set of
 * [MemoryEntity] rows (my profile / facts / preferences / style). Those rows are
 * then fed back into reply generation so suggestions sound more like me.
 *
 * Singleton, mirroring [SettingsRepository] — background services and the UI all
 * talk to the one instance.
 */
class MemoryRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dao = MemoryDatabase.get(appContext).memoryDao()
    private val settingsRepo = SettingsRepository.get(appContext)

    /** Human-readable, exportable record of everything I've said. */
    private val replyFile = File(appContext.filesDir, "my_replies.txt")
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ioLock = Mutex() // serialises file + learning access

    // Bounded set of recently logged replies so we don't record the same
    // on-screen message every time the accessibility reader re-scans the window.
    private val recent = LinkedHashSet<String>()

    private val _memories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val memories: StateFlow<List<MemoryEntity>> = _memories

    /** Soft-deleted memories (recycle bin): restorable, not yet purged. */
    private val _deleted = MutableStateFlow<List<MemoryEntity>>(emptyList())
    val deleted: StateFlow<List<MemoryEntity>> = _deleted

    private val _stats = MutableStateFlow(MemoryStats())
    val stats: StateFlow<MemoryStats> = _stats

    init {
        scope.launch { initLoad() }
    }

    private suspend fun initLoad() = ioLock.withLock {
        val lines = if (replyFile.exists()) replyFile.readLines().filter { it.isNotBlank() } else emptyList()
        lines.takeLast(RECENT_MEMORY).forEach { recent.add(textOf(it)) }
        val learned = prefs.getInt(KEY_LEARNED, 0).coerceIn(0, lines.size)
        _stats.value = _stats.value.copy(totalReplies = lines.size, learnedReplies = learned)
        refreshMemories()
    }

    // ---- recording ------------------------------------------------------

    /** Record every message I authored in [conv] (skips the other party). */
    fun recordMyMessages(conv: Conversation) {
        if (!settingsRepo.current().memoryEnabled) return
        val mine = conv.messages.filter { it.speaker == Speaker.ME }.map { it.text }
        if (mine.isEmpty()) return
        scope.launch { mine.forEach { appendReply(it) }; maybeLearn() }
    }

    /** Record a single reply I chose/sent through the assistant. */
    fun recordReply(text: String) {
        if (!settingsRepo.current().memoryEnabled) return
        scope.launch { appendReply(text); maybeLearn() }
    }

    private suspend fun appendReply(raw: String) = ioLock.withLock {
        val text = normalize(raw)
        if (text.isEmpty() || !recent.add(text)) return@withLock
        if (recent.size > RECENT_MEMORY) recent.iterator().let { it.next(); it.remove() }
        runCatching {
            replyFile.appendText("${System.currentTimeMillis()}\t$text\n")
        }
        _stats.value = _stats.value.copy(totalReplies = _stats.value.totalReplies + 1)
    }

    // ---- learning -------------------------------------------------------

    /** Kick off learning if enabled and enough new replies have accumulated. */
    private suspend fun maybeLearn() {
        val settings = settingsRepo.current()
        if (!settings.memoryEnabled || _stats.value.learning) return
        if (_stats.value.pending < settings.learnThreshold) return
        runLearning(settings)
    }

    /** Manual trigger from the UI; learns whatever is pending regardless of threshold. */
    suspend fun learnNow() {
        val settings = settingsRepo.current()
        if (_stats.value.learning || _stats.value.pending <= 0) return
        runLearning(settings)
    }

    private suspend fun runLearning(settings: com.octos.aichat.data.AppSettings) {
        if (settings.apiKey.isBlank()) return
        _stats.value = _stats.value.copy(learning = true)
        try {
            val (newReplies, total) = ioLock.withLock {
                val lines = if (replyFile.exists()) replyFile.readLines().filter { it.isNotBlank() } else emptyList()
                val from = prefs.getInt(KEY_LEARNED, 0).coerceIn(0, lines.size)
                lines.subList(from, lines.size).map { textOf(it) } to lines.size
            }
            if (newReplies.isEmpty()) return
            val existing = dao.all()
            val result = AiRepository.learnMemory(settings, existing, newReplies) ?: return

            // Memory is permanent: only add new rows and overwrite the specific
            // existing rows the learner flagged as conflicting (newest wins).
            val now = System.currentTimeMillis()
            val knownIds = existing.mapTo(HashSet()) { it.id }
            result.updates.filter { it.id in knownIds }.forEach {
                dao.updateFull(it.id, it.category, it.content, it.keywords, it.importance, now)
            }
            // Soft-delete so a wrong call stays recoverable from the recycle bin.
            result.deletions.filter { it in knownIds }.forEach {
                dao.softDelete(it, now, DELETED_BY_LEARNER)
            }
            if (result.additions.isNotEmpty()) {
                dao.insertAll(result.additions.map {
                    MemoryEntity(
                        category = it.category,
                        content = it.content,
                        updatedAt = now,
                        keywords = it.keywords,
                        importance = it.importance,
                    )
                })
            }
            prefs.edit().putInt(KEY_LEARNED, total).apply()
            _stats.value = _stats.value.copy(learnedReplies = total)
            refreshMemories()
        } catch (_: Exception) {
            // Leave the learned offset untouched so it retries on the next trigger.
        } finally {
            _stats.value = _stats.value.copy(learning = false)
        }
    }

    // ---- reads ----------------------------------------------------------

    /**
     * Formatted memory for injection into the reply-generation system prompt.
     * Everything is kept in the DB forever, but we only inject the top-N items so
     * the prompt stays bounded as memory grows. When [conv] is supplied we rank by
     * the three-factor score (recency + importance + relevance-to-this-chat);
     * otherwise we fall back to most-recently-updated.
     */
    fun memoryBlock(conv: Conversation? = null): String {
        val settings = settingsRepo.current()
        if (!settings.memoryEnabled) return ""
        val items = _memories.value
        if (items.isEmpty()) return ""
        val limit = settings.memoryInjectLimit.coerceAtLeast(1)
        // Query = the recent transcript; the other party's latest lines are what we
        // are about to reply to, so relevance should favour memories about them.
        val query = conv?.toTranscript(maxLines = 12).orEmpty()
        val selected = MemoryRetrieval.retrieve(
            items = items,
            query = query,
            now = System.currentTimeMillis(),
            k = limit,
        )
        return selected
            .groupBy { it.category }
            .entries.joinToString("\n") { (cat, rows) ->
                "【$cat】" + rows.joinToString("；") { it.content }
            }
    }

    /** Manually remove a memory item — soft-delete so it can be restored. */
    suspend fun deleteMemory(id: Long) {
        dao.softDelete(id, System.currentTimeMillis(), DELETED_BY_USER)
        refreshMemories()
    }

    /** Restore a soft-deleted memory from the recycle bin back to active. */
    suspend fun restoreMemory(id: Long) {
        dao.restore(id)
        refreshMemories()
    }

    /** Permanently drop a memory from the recycle bin (unrecoverable). */
    suspend fun purgeMemory(id: Long) {
        dao.deleteById(id)
        refreshMemories()
    }

    /** Manually edit a memory item's text (keeps its category). */
    suspend fun editMemory(id: Long, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val item = _memories.value.firstOrNull { it.id == id } ?: return
        dao.update(id, item.category, text, System.currentTimeMillis())
        refreshMemories()
    }

    suspend fun clearAll() = ioLock.withLock {
        dao.clear()
        runCatching { if (replyFile.exists()) replyFile.delete() }
        prefs.edit().putInt(KEY_LEARNED, 0).apply()
        recent.clear()
        _memories.value = emptyList()
        _deleted.value = emptyList()
        _stats.value = MemoryStats()
    }

    private suspend fun refreshMemories() {
        _memories.value = dao.all()
        _deleted.value = dao.allDeleted()
    }

    // ---- helpers --------------------------------------------------------

    /** One reply per line as "epochMillis\ttext"; grab the text half. */
    private fun textOf(line: String): String = line.substringAfter('\t', line).trim()

    private fun normalize(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")

    companion object {
        private const val PREFS = "ai_chat_memory"
        private const val KEY_LEARNED = "learned_reply_count"
        private const val RECENT_MEMORY = 300
        private const val DELETED_BY_LEARNER = "learner"
        private const val DELETED_BY_USER = "user"

        @Volatile
        private var INSTANCE: MemoryRepository? = null

        fun get(context: Context): MemoryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryRepository(context).also { INSTANCE = it }
            }
    }
}
