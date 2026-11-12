package com.octos.aichat.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One consolidated piece of long-term memory about the user — distilled by the
 * LLM from the raw reply log. [category] is one of 画像 / 事实 / 偏好 / 风格;
 * [content] is a short standalone sentence (e.g. "在北京做程序员").
 */
@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val content: String,
    val updatedAt: Long,
)

/** A brand-new memory item the learner wants to add (no id/timestamp yet). */
data class MemoryDraft(
    val category: String,
    val content: String,
)

/** An update to an existing memory row — conflict resolution, newest wins. */
data class MemoryEdit(
    val id: Long,
    val category: String,
    val content: String,
)

/**
 * The learner's verdict for one round. Memory is never wiped: we only ever add
 * [additions] and overwrite the specific rows named in [updates].
 */
data class MemoryLearnResult(
    val additions: List<MemoryDraft>,
    val updates: List<MemoryEdit>,
)
