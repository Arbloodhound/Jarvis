package com.agent.ultra.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val fromUser: Boolean,
    val text: String,
    val timestamp: Long,
)

/**
 * A named, replayable tool sequence — the user's own saved routine.
 * `stepsJson` is a JSON array of {"tool":..., "params":{...}} objects, in order.
 */
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val name: String,
    val stepsJson: String,
    val createdAt: Long,
    val lastRun: Long,
    val runCount: Int,
)
