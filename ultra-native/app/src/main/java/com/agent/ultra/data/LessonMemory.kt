package com.agent.ultra.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.agent.ultra.agent.Experience

/** One lesson (see [Experience]). Counts are what happened after it was served. */
@Entity(tableName = "lessons")
data class LessonEntity(
    @PrimaryKey val uid: String,
    val whenText: String,
    val text: String,
    val source: String,
    val createdAt: Long,
    val served: Int,
    val ok: Int,
    val fail: Int,
) {
    fun lesson() = Experience.Lesson(uid, whenText, text, source, createdAt, served, ok, fail)

    companion object {
        fun of(l: Experience.Lesson) =
            LessonEntity(l.uid, l.whenText, l.text, l.source, l.createdAt, l.served, l.ok, l.fail)
    }
}

@Dao
interface LessonDao {
    @Query("SELECT * FROM lessons ORDER BY createdAt DESC LIMIT 500")
    suspend fun all(): List<LessonEntity>

    @Query("SELECT * FROM lessons WHERE uid = :uid")
    suspend fun byUid(uid: String): LessonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(l: LessonEntity)

    @Query("UPDATE lessons SET served = served + 1, ok = ok + :ok, fail = fail + :fail WHERE uid = :uid")
    suspend fun outcome(uid: String, ok: Int, fail: Int)

    /** A verdict arrived after the run was counted: move one outcome, don't count a new serve. */
    @Query("UPDATE lessons SET ok = MAX(ok + :ok, 0), fail = MAX(fail + :fail, 0) WHERE uid = :uid")
    suspend fun recount(uid: String, ok: Int, fail: Int)

    @Query("DELETE FROM lessons WHERE uid = :uid")
    suspend fun delete(uid: String)
}
