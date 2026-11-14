package com.octos.aichat.data.memory

import android.content.Context
import com.octos.aichat.ai.AiService
import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.data.AppSettings
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

/** Snapshot of memory state for the settings UI. */
data class MemoryStats(
    val totalReplies: Int = 0,
    val learnedReplies: Int = 0,
    val learning: Boolean = false,
) {
    val pending: Int get() = (totalReplies - learnedReplies).coerceAtLeast(0)
}

/**
 * Coordinates the long-term memory feature, delegating the real work to focused
 * collaborators: [ReplyLog] (raw reply record), [AiService] (the learner),
 * [MemoryDao] (persistence), [MemoryEmbedder] (vectors) and [MemoryInjector]
 * (prompt block). Owns only the orchestration + the UI-facing state flows.
 *
 * Singleton, mirroring [SettingsRepository] — background services and the UI all
 * talk to the one instance.
 */
class MemoryRepository private constructor(context: Context) : MemoryStore {

    private val appContext = context.applicationContext
    private val dao = MemoryDatabase.get(appContext).memoryDao()
    private val settingsRepo = SettingsRepository.get(appContext)
    private val replyLog = ReplyLog(appContext)
    private val embedder = MemoryEmbedder(appContext, dao)
    private val learningPass = MemoryLearningPass(replyLog, dao)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val learnLock = Mutex() // only one learning pass at a time

    private val _memories = MutableStateFlow<List<MemoryEntity>>(emptyList())
    override val memories: StateFlow<List<MemoryEntity>> = _memories

    /** Soft-deleted memories (recycle bin): restorable, not yet purged. */
    private val _deleted = MutableStateFlow<List<MemoryEntity>>(emptyList())
    override val deleted: StateFlow<List<MemoryEntity>> = _deleted

    private val _stats = MutableStateFlow(MemoryStats())
    override val stats: StateFlow<MemoryStats> = _stats

    init {
        scope.launch {
            val counts = replyLog.primeRecent()
            _stats.value = _stats.value.copy(totalReplies = counts.total, learnedReplies = counts.learned)
            refreshMemories()
            prepareEmbedding()
        }
    }

    // ---- recording ------------------------------------------------------

    /** Record every message I authored in [conv] (skips the other party). */
    override fun recordMyMessages(conv: Conversation) {
        if (!settingsRepo.current().memoryEnabled) return
        val mine = conv.messages.filter { it.speaker == Speaker.ME }.map { it.text }
        if (mine.isEmpty()) return
        scope.launch {
            val added = mine.count { replyLog.append(it) }
            if (added > 0) bumpTotal(added)
            learnAfterRecordIfCloud()
        }
    }

    /** Record a single reply I chose/sent through the assistant. */
    override fun recordReply(text: String) {
        if (!settingsRepo.current().memoryEnabled) return
        scope.launch {
            if (replyLog.append(text)) bumpTotal(1)
            learnAfterRecordIfCloud()
        }
    }

    private fun bumpTotal(n: Int) {
        _stats.value = _stats.value.copy(totalReplies = _stats.value.totalReplies + n)
    }

    // ---- learning -------------------------------------------------------

    override fun learnIfDue() {
        scope.launch { maybeLearn() }
    }

    /**
     * Recording happens on a bubble tap, right before that tap's reply generation.
     * In local mode both need the single on-device engine, so learning here could
     * make the user wait behind a whole learning inference (up to its 30 s limit).
     * Local learning therefore waits for [learnIfDue] (user left the chat app);
     * cloud requests run in parallel, so cloud learns right away.
     */
    private suspend fun learnAfterRecordIfCloud() {
        if (!settingsRepo.current().useLocal) maybeLearn()
    }

    /** Kick off learning if enabled and enough new replies have accumulated. */
    private suspend fun maybeLearn() {
        val settings = settingsRepo.current()
        if (!settings.memoryEnabled || _stats.value.learning) return
        if (_stats.value.pending < settings.learnThreshold) return
        runLearning(settings)
    }

    /** Manual trigger from the UI; learns whatever is pending regardless of threshold. */
    override suspend fun learnNow() {
        val settings = settingsRepo.current()
        if (_stats.value.learning || _stats.value.pending <= 0) return
        runLearning(settings)
    }

    private suspend fun runLearning(settings: AppSettings) = learnLock.withLock {
        // Cloud needs an API key. Local learns on-device and must never depend on
        // (or fall back to) the cloud key — its readiness is the model, checked below.
        if (!settings.useLocal && settings.apiKey.isBlank()) return
        _stats.value = _stats.value.copy(learning = true)
        try {
            // Loading can take seconds, so it happens after the UI shows "learning".
            // Idempotent when the bubble already loaded the same model.
            if (settings.useLocal && !LlmEngine.ensureLoaded(appContext, settings.localModel)) return
            val learned = learningPass.run { existing, replies ->
                AiService.instance.learnMemory(settings, existing, replies)
            } ?: return
            _stats.value = _stats.value.copy(learnedReplies = learned)
            refreshMemories()
            if (embedder.backfill()) refreshMemories()
        } catch (_: Exception) {
            // MemoryLearningPass leaves the learned offset untouched, so it retries on the next trigger.
        } finally {
            _stats.value = _stats.value.copy(learning = false)
        }
    }

    // ---- reads ----------------------------------------------------------

    /** Formatted memory for injection into the reply-generation system prompt. */
    override fun memoryBlock(conv: Conversation?): String =
        MemoryInjector.block(_memories.value, settingsRepo.current(), conv)

    // ---- manual edits ---------------------------------------------------

    /** Manually remove a memory item — soft-delete so it can be restored. */
    override suspend fun deleteMemory(id: Long) {
        dao.softDelete(id, System.currentTimeMillis(), DELETED_BY_USER)
        refreshMemories()
    }

    /** Restore a soft-deleted memory from the recycle bin back to active. */
    override suspend fun restoreMemory(id: Long) {
        dao.restore(id)
        refreshMemories()
    }

    /** Permanently drop a memory from the recycle bin (unrecoverable). */
    override suspend fun purgeMemory(id: Long) {
        dao.deleteById(id)
        refreshMemories()
    }

    /** Manually edit a memory item's text (keeps its category). */
    override suspend fun editMemory(id: Long, content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        val item = _memories.value.firstOrNull { it.id == id } ?: return
        dao.update(id, item.category, text, System.currentTimeMillis())
        dao.clearEmbedding(id) // text changed → recompute vector
        refreshMemories()
        if (embedder.backfill()) refreshMemories()
    }

    override suspend fun clearAll() {
        dao.clear()
        replyLog.clear()
        _memories.value = emptyList()
        _deleted.value = emptyList()
        _stats.value = MemoryStats()
    }

    // ---- internals ------------------------------------------------------

    /** Load the on-device embedding model (if enabled) and backfill missing vectors. */
    private suspend fun prepareEmbedding() {
        if (embedder.prepare(settingsRepo.current().memoryEmbeddingEnabled) && embedder.backfill()) {
            refreshMemories()
        }
    }

    private suspend fun refreshMemories() {
        _memories.value = dao.all()
        _deleted.value = dao.allDeleted()
    }

    companion object {
        private const val DELETED_BY_USER = "user"

        @Volatile
        private var INSTANCE: MemoryRepository? = null

        fun get(context: Context): MemoryRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MemoryRepository(context).also { INSTANCE = it }
            }
    }
}
