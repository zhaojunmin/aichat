package com.octos.aichat.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One consolidated piece of long-term memory about the user — distilled by the
 * LLM from the raw reply log. [category] is one of 画像 / 事实 / 偏好 / 风格;
 * [content] is a short standalone sentence (e.g. "在北京做程序员").
 *
 * [keywords] and [importance] back the three-factor retrieval scoring
 * (recency + importance + relevance): [keywords] is a space-separated list of
 * salient terms the learner extracted (used by the local lexical relevance
 * scorer), [importance] is the learner's 1-10 poignancy score, pre-computed at
 * write time so retrieval needs no extra LLM call.
 *
 * Deletes are *soft*: [deletedAt] = 0 means active; > 0 is the removal timestamp
 * and the row moves to the recycle bin instead of being dropped. LLM-driven
 * DELETE is error-prone (especially with on-device models), so removals stay
 * auditable and restorable. [deletedBy] records who removed it ("learner" / "user").
 *
 * [embedding] is the on-device bge-small-zh sentence vector (512×float32 little-endian,
 * or null if not yet computed), used by the semantic relevance scorer. [embeddingVersion]
 * tracks the model/pooling version so changing it triggers a lazy re-embed (0 = none yet).
 */
@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val content: String,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "") val keywords: String = "",
    @ColumnInfo(defaultValue = "5") val importance: Int = 5,
    @ColumnInfo(defaultValue = "0") val deletedAt: Long = 0,
    @ColumnInfo(defaultValue = "") val deletedBy: String = "",
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null,
    @ColumnInfo(defaultValue = "0") val embeddingVersion: Int = 0,
)

/** A brand-new memory item the learner wants to add (no id/timestamp yet). */
data class MemoryDraft(
    val category: String,
    val content: String,
    val keywords: String = "",
    val importance: Int = 5,
)

/** An update to an existing memory row — conflict resolution, newest wins. */
data class MemoryEdit(
    val id: Long,
    val category: String,
    val content: String,
    val keywords: String = "",
    val importance: Int = 5,
)

/**
 * The learner's verdict for one round (Mem0-style ADD / UPDATE / DELETE / NOOP):
 * [additions] are new rows, [updates] overwrite existing rows by id (conflict →
 * newest wins), [deletions] are ids of memories that are now clearly obsolete.
 * Deletes are applied as soft-deletes, so a wrong call stays recoverable.
 */
data class MemoryLearnResult(
    val additions: List<MemoryDraft>,
    val updates: List<MemoryEdit>,
    val deletions: List<Long> = emptyList(),
)
