package com.octos.aichat.data.memory

/**
 * One learning round: read the un-learned replies, ask the learner, apply its
 * verdict to the memory table, and only then advance the learned offset.
 *
 * The ordering is the whole point (reliability scenario R5): if anything fails —
 * the learner returns nothing, throws, or a write fails part-way — the offset is
 * left untouched, so the same replies are retried on the next trigger instead of
 * being silently dropped. Kept free of Android types so it runs on the JVM.
 */
internal class MemoryLearningPass(
    private val ledger: ReplyLedger,
    private val dao: MemoryDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * @return the new learned offset when memory was updated, or null when there
     *   was nothing to learn or the learner gave no usable result. Exceptions from
     *   [learn] or the DAO propagate; in every non-success case the offset is unchanged.
     */
    suspend fun run(
        learn: suspend (existing: List<MemoryEntity>, replies: List<String>) -> MemoryLearnResult?,
    ): Int? {
        val pending = ledger.pending()
        if (pending.replies.isEmpty()) return null
        val existing = dao.all()
        val result = learn(existing, pending.replies) ?: return null

        // Memory is permanent: add new rows, overwrite conflicting rows (newest
        // wins), and soft-delete clearly-obsolete rows (recoverable). Ids the
        // learner made up are ignored so it can never touch rows it wasn't shown.
        val now = clock()
        val knownIds = existing.mapTo(HashSet()) { it.id }
        result.updates.filter { it.id in knownIds }.forEach {
            dao.updateFull(it.id, it.category, it.content, it.keywords, it.importance, now)
            dao.clearEmbedding(it.id) // text changed → stale vector, let backfill recompute
        }
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
        ledger.markLearned(pending.total)
        return pending.total
    }

    companion object {
        const val DELETED_BY_LEARNER = "learner"
    }
}
