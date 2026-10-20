package com.agent.jarvis.harness

import androidx.room.Dao
import androidx.room.Query

/**
 * Hard deletion of memory claims, for the forget flow only ([Forget]). [ClaimDao] can supersede or
 * retract a claim, but a retracted claim still holds its text, so "forget X" needs a real delete.
 * Kept separate so ordinary memory code never reaches it.
 */
@Dao
interface ClaimForgetDao {
    @Query("SELECT * FROM memory_claims")
    suspend fun all(): List<ClaimEntity>

    @Query("DELETE FROM memory_claims WHERE id = :id")
    suspend fun delete(id: String): Int
}
