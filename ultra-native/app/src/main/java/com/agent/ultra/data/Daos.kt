package com.agent.ultra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun listConversations(): List<ConversationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(c: ConversationEntity)

    @Query("UPDATE conversations SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY timestamp ASC")
    suspend fun messagesFor(cid: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(m: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :cid")
    suspend fun deleteMessages(cid: String)

    @Query("DELETE FROM conversations WHERE id = :cid")
    suspend fun deleteConversation(cid: String)
}
