package com.octos.aichat.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Learner bookkeeping: how many reply-log lines have been learned. One row. It
 * lives in the memory database (not SharedPreferences) so that applying a
 * learning result and advancing this offset commit in one transaction — a crash
 * in between can neither lose replies nor make them be learned twice.
 */
@Entity(tableName = "learning_state")
data class LearningState(@PrimaryKey val id: Int = SINGLE_ROW, val learnedReplies: Int) {
    companion object {
        const val SINGLE_ROW = 0
    }
}

@Dao
interface LearningStateDao {

    /** Null until first written (fresh install, or not yet migrated from prefs). */
    @Query("SELECT learnedReplies FROM learning_state WHERE id = 0")
    suspend fun learnedReplies(): Int?

    @Upsert
    suspend fun set(state: LearningState)
}
