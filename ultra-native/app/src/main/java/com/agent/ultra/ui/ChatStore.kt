package com.agent.ultra.ui

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.agent.ultra.data.ConversationEntity
import com.agent.ultra.data.MessageEntity
import com.agent.ultra.data.UltraDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * The conversation store: Compose-visible state backed by Room.
 * messages = the open conversation; conversations = the drawer list.
 * Every append persists; process death no longer erases anything.
 */
object ChatStore {
    val messages = mutableStateListOf<ChatMessage>()
    val conversations = mutableStateListOf<ConversationEntity>()
    var conversationId = mutableStateOf<String?>(null)

    private var dao: com.agent.ultra.data.ConversationDao? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val ui = CoroutineScope(Dispatchers.Main.immediate)

    /**
     * Agent work outlives the screen it was started from.
     *
     * Runs used to be launched from the chat screen's own composition scope,
     * so opening Settings mid-task cancelled the task and the user got
     * "brain fault - The coroutine scope left the composition". A run belongs
     * to the app, not to whichever screen happens to be showing.
     */
    val agentScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)

    /** True while a run is in flight — process-wide, for the same reason. */
    val thinking = mutableStateOf(false)

    /**
     * Compose snapshot state is not thread-safe. Streaming answers arrive on
     * OkHttp's IO threads and the on-device model emits from its own worker,
     * so every mutation of [messages] funnels through here. Writing it from a
     * background thread crashes the process later, in the global snapshot
     * observer, far from the line that did it.
     */
    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else ui.launch { block() }
    }

    suspend fun init(context: Context) {
        val db = UltraDatabase.get(context)
        dao = db.conversations()
        val list = withContext(Dispatchers.IO) { db.conversations().listConversations() }
        conversations.clear(); conversations.addAll(list)
        if (conversationId.value == null) {
            val current = list.firstOrNull()
            if (current != null) openConversation(current.id) else newChat()
        }
    }

    suspend fun newChat() {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val conv = ConversationEntity(id, "New chat", now, now)
        withContext(Dispatchers.IO) { dao?.upsertConversation(conv) }
        conversations.add(0, conv)
        conversationId.value = id
        messages.clear()
    }

    suspend fun openConversation(id: String) {
        val msgs = withContext(Dispatchers.IO) { dao?.messagesFor(id) ?: emptyList() }
        conversationId.value = id
        messages.clear()
        messages.addAll(msgs.map { ChatMessage(it.fromUser, it.text, it.id, it.timestamp) })
    }

    suspend fun deleteConversation(id: String) {
        withContext(Dispatchers.IO) {
            dao?.deleteMessages(id)
            dao?.deleteConversation(id)
        }
        conversations.removeAll { it.id == id }
        if (conversationId.value == id) {
            if (conversations.isNotEmpty()) openConversation(conversations.first().id)
            else newChat()
        }
    }

    /** Append to the visible conversation and persist. Title from first user message. */
    fun add(msg: ChatMessage) {
        onUi { messages.add(msg) }
        persist(msg)
    }

    /** State-only append for streaming bubbles; call persist() with the final content. */
    fun addToState(msg: ChatMessage) {
        onUi { messages.add(msg) }
    }

    /** Append a streamed piece to a bubble, addressed by id rather than index —
     * indices shift when anything else is added or removed mid-stream. */
    fun appendTo(id: String, piece: String) = onUi {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = messages[i].copy(text = messages[i].text + piece)
    }

    /** Replace a bubble's whole text. */
    fun setText(id: String, text: String) = onUi {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = messages[i].copy(text = text)
    }

    fun removeById(id: String) = onUi {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages.removeAt(i)
    }

    /** Current text of a bubble, or empty if it is gone. */
    fun textOf(id: String): String = messages.firstOrNull { it.id == id }?.text ?: ""

    fun messageById(id: String): ChatMessage? = messages.firstOrNull { it.id == id }

    /** Insert-or-replace a message row (used for final streamed content). */
    fun persist(msg: ChatMessage) {
        val cid = conversationId.value ?: return
        val d = dao ?: return
        scope.launch {
            d.insertMessage(MessageEntity(msg.id, cid, msg.fromUser, msg.text, msg.timestamp))
            d.touch(cid, System.currentTimeMillis())
            if (msg.fromUser) {
                conversations.find { it.id == cid }?.let { conv ->
                    if (conv.title == "New chat") {
                        val title = msg.text.take(40)
                        val updated = conv.copy(title = title, updatedAt = System.currentTimeMillis())
                        d.upsertConversation(updated)
                        val idx = conversations.indexOfFirst { it.id == cid }
                        if (idx >= 0) conversations[idx] = updated
                    }
                }
            }
        }
    }
}
