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
                dao.update(it.id, it.category, it.content, now)
            }
            if (result.additions.isNotEmpty()) {
                dao.insertAll(result.additions.map {
                    MemoryEntity(category = it.category, content = it.content, updatedAt = now)
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
     * Everything is kept in the DB forever, but we only inject the most-recently
     * updated N items so the prompt stays bounded as memory grows.
     */
    fun memoryBlock(): String {
        val settings = settingsRepo.current()
        if (!settings.memoryEnabled) return ""
        val items = _memories.value
        if (items.isEmpty()) return ""
        val recentItems = items
            .sortedWith(compareByDescending<MemoryEntity> { it.updatedAt }.thenByDescending { it.id })
            .take(settings.memoryInjectLimit.coerceAtLeast(1))
        return recentItems
            .groupBy { it.category }
            .entries.joinToString("\n") { (cat, rows) ->
                "【$cat】" + rows.joinToString("；") { it.content }
            }
    }

    suspend fun clearAll() = ioLock.withLock {
        dao.clear()
        runCatching { if (replyFile.exists()) replyFile.delete() }
        prefs.edit().putInt(KEY_LEARNED, 0).apply()
        recent.clear()
        _memories.value = emptyList()
        _stats.value = MemoryStats()
    }

    private suspend fun refreshMemories() {
        _memories.value = dao.all()
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

        @Volatile
        private var INSTANCE: MemoryRepository? = null

        fun get(context: Context): MemoryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryRepository(context).also { INSTANCE = it }
            }
    }
}
