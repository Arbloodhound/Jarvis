package com.agent.ultra.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * What the agent has learned about a screen.
 *
 * The reader derives the same answer for the same screen every time and then
 * throws it away. This keeps it. The second visit to a screen does not have to
 * rediscover which of its nodes are the records — it already knows, and can
 * check the remembered answer instead of scoring every candidate again.
 *
 * Only structure is stored. No text from the screen is written here, so
 * nothing about what the user was reading, buying or messaging is persisted —
 * a memory row is the shape of a page, not its contents.
 */
@Entity(tableName = "screen_memory")
data class ScreenMemoryEntity(
    /** "pkg/digest" from [com.agent.ultra.agent.ScreenSignature]. */
    @PrimaryKey val screenKey: String,
    val pkg: String,
    /** Structural signature of the record template that won here. */
    val template: String,
    /** How many records that template produced last time, as a sanity check
     * against a remembered template that has since stopped fitting. */
    val recordCount: Int,
    /** Field names this screen is known to expose, comma separated. Names
     * only — never values. */
    val fieldsCsv: String,
    /** The screen's controls as JSON: view id and kind only, never a label
     * and never a value. See [com.agent.ultra.agent.ScreenControls]. */
    val controlsJson: String = "",
    val seenCount: Int,
    val lastSeen: Long,
    /** IDS or CLASSES: how the screen was recognised. A CLASSES match is a
     * hint rather than an identification. */
    val confidence: String,
)

@Dao
interface ScreenMemoryDao {

    @Query("SELECT * FROM screen_memory WHERE screenKey = :key")
    suspend fun get(key: String): ScreenMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ScreenMemoryEntity)

    @Query("SELECT COUNT(*) FROM screen_memory")
    suspend fun count(): Int

    @Query("SELECT * FROM screen_memory WHERE pkg = :pkg ORDER BY seenCount DESC LIMIT 50")
    suspend fun forApp(pkg: String): List<ScreenMemoryEntity>

    /** Screens are cheap to relearn and there is no value in an unbounded
     * table, so the least-used rows go first. */
    @Query(
        "DELETE FROM screen_memory WHERE screenKey NOT IN " +
            "(SELECT screenKey FROM screen_memory ORDER BY seenCount DESC, lastSeen DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM screen_memory")
    suspend fun clear()
}
