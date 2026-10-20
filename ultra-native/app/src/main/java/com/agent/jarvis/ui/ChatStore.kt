package com.agent.jarvis.ui

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.agent.jarvis.data.ConversationEntity
import com.agent.jarvis.data.MessageEntity
import com.agent.jarvis.data.JarvisDatabase
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
    /** The chat's Focus menu: Normal, or answer from Memory / Notes / both ([MemoryFocus]). */
    val focus = mutableStateOf(com.agent.jarvis.agent.MemoryFocus.Focus.NORMAL)
    /** A screen the brain was asked to open ("open my notes"); MainActivity takes and clears it. */
    val screenRequest = mutableStateOf<String?>(null)
    /** MainActivity is in front (resumed): a screen request can be shown. */
    @Volatile var uiVisible = false
    val conversations = mutableStateListOf<ConversationEntity>()
    var conversationId = mutableStateOf<String?>(null)

    private var dao: com.agent.jarvis.data.ConversationDao? = null
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
     * What the on-device model is doing while nothing visible arrives (see [ChatStatus]).
     * Shown under the chat, never added to [messages]: not persisted, never spoken.
     * Written only through [setStatus]/[clearStatus], which go through [onUi].
     */
    val status = mutableStateOf<ChatStatus.Status?>(null)

    /** Start a status episode; returns it so the caller can clear exactly this one. */
    fun setStatus(phase: ChatStatus.Phase, detail: String = ""): ChatStatus.Status =
        ChatStatus.Status(phase, System.currentTimeMillis(), detail).also { s ->
            ChatStatus.thoughtOf(s)?.let { lastThought = it }
            onUi { status.value = s }
        }

    /**
     * The model's thinking, kept with the answer it led to (DeepSeek-style: tap to read it all).
     * Keyed by message id; this session only, not saved with the chat.
     */
    val thoughts = mutableStateMapOf<String, String>()
    @Volatile private var lastThought = ""

    /** The latest thinking goes with the next agent message; a new question starts fresh. */
    private fun attachThought(msg: ChatMessage) {
        val t = lastThought
        lastThought = ""
        if (!msg.fromUser && t.isNotBlank()) onUi { thoughts[msg.id] = t }
    }

    /** Clear [s] if it is still the one showing (a newer episode is left alone). Any thread. */
    fun clearStatus(s: ChatStatus.Status) = onUi { if (status.value === s) status.value = null }

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
        val db = JarvisDatabase.get(context)
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

    /** Remove message [id] and everything after it, on screen and on disk (editing a message
     *  re-asks from there). */
    suspend fun truncateFrom(id: String) {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0) return
        val gone = messages.subList(i, messages.size).map { it.id }
        withContext(Dispatchers.IO) { gone.forEach { dao?.deleteMessage(it) } }
        while (messages.size > i) messages.removeAt(messages.size - 1)
    }

    /** A new conversation holding this one up to and including message [id], opened: try another
     *  direction from that point without losing this one. */
    suspend fun branchFrom(id: String) {
        val i = messages.indexOfFirst { it.id == id }
        if (i < 0) return
        val keep = messages.subList(0, i + 1).toList()
        val title = "Branch: " + (keep.firstOrNull { it.fromUser }?.text?.take(32) ?: "chat")
        val cid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val copies = keep.map { it.copy(id = UUID.randomUUID().toString()) }
        withContext(Dispatchers.IO) {
            dao?.upsertConversation(ConversationEntity(cid, title, now, now))
            copies.forEach { dao?.insertMessage(MessageEntity(it.id, cid, it.fromUser, it.text, it.timestamp)) }
        }
        conversations.add(0, ConversationEntity(cid, title, now, now))
        conversationId.value = cid
        messages.clear(); messages.addAll(copies)
    }

    /** Append to the visible conversation and persist. Title from first user message. */
    fun add(msg: ChatMessage) {
        attachThought(msg)
        onUi { messages.add(msg) }
        persist(msg)
    }

    /** State-only append for streaming bubbles; call persist() with the final content. */
    fun addToState(msg: ChatMessage) {
        attachThought(msg)
        onUi { messages.add(msg) }
    }

    /** Append a streamed piece to a bubble, addressed by id rather than index —
     * indices shift when anything else is added or removed mid-stream. */
    fun appendTo(id: String, piece: String) {
        // Pieces are batched: one redraw per [STREAM_FLUSH_MS] instead of one main-thread hop and
        // one bubble rebuild per token (CROSSEVAL_OPENCLAW §2). [textOf]/[messageById] include
        // what is still pending, so nothing reading the bubble sees it short.
        val sb = pendingPieces.getOrPut(id) { StringBuilder() }
        synchronized(sb) { sb.append(piece) }
        if (!flushScheduled) {
            flushScheduled = true
            ui.launch { kotlinx.coroutines.delay(STREAM_FLUSH_MS); flushPieces() }
        }
    }

    private val pendingPieces = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()
    @Volatile private var flushScheduled = false
    private const val STREAM_FLUSH_MS = 40L

    private fun pendingOf(id: String): String = pendingPieces[id]?.let { synchronized(it) { it.toString() } }.orEmpty()

    /** Main thread: moves batched pieces into their bubbles. */
    private fun flushPieces() {
        flushScheduled = false
        for ((id, sb) in pendingPieces) {
            val add = synchronized(sb) { sb.toString().also { sb.setLength(0) } }
            if (add.isEmpty()) { pendingPieces.remove(id, sb); continue }
            val i = messages.indexOfFirst { it.id == id }
            if (i >= 0) messages[i] = messages[i].copy(text = messages[i].text + add)
        }
    }

    /** Replace a bubble's whole text (pieces still pending for it are dropped: the text replaces them). */
    fun setText(id: String, text: String) {
        pendingPieces.remove(id)
        onUi {
            val i = messages.indexOfFirst { it.id == id }
            if (i >= 0) messages[i] = messages[i].copy(text = text)
        }
    }

    fun removeById(id: String) {
        pendingPieces.remove(id)
        onUi {
            val i = messages.indexOfFirst { it.id == id }
            if (i >= 0) messages.removeAt(i)
        }
    }

    /** Current text of a bubble (with pieces not yet drawn), or empty if it is gone. */
    fun textOf(id: String): String = messages.firstOrNull { it.id == id }?.text?.plus(pendingOf(id)) ?: ""

    fun messageById(id: String): ChatMessage? = messages.firstOrNull { it.id == id }?.let { m ->
        pendingOf(id).takeIf { it.isNotEmpty() }?.let { m.copy(text = m.text + it) } ?: m
    }

    /** Insert-or-replace a message row (used for final streamed content). */
    fun persist(msg: ChatMessage) {
        val cid = conversationId.value ?: return
        val d = dao ?: return
        scope.launch {
            d.insertMessage(MessageEntity(msg.id, cid, msg.fromUser, msg.text, msg.timestamp))
            d.touch(cid, System.currentTimeMillis())
            if (msg.fromUser) {
                // The drawer list is snapshot state: mutate it on the main thread
                // only, and do the DB write back here on IO.
                onUi {
                    val idx = conversations.indexOfFirst { it.id == cid }
                    if (idx >= 0 && conversations[idx].title == "New chat") {
                        val updated = conversations[idx].copy(
                            title = msg.text.take(40), updatedAt = System.currentTimeMillis())
                        conversations[idx] = updated
                        scope.launch { d.upsertConversation(updated) }
                    }
                }
            }
        }
    }

    // ── The chat screen's brain ──────────────────────────────────────────
    // Owned by the process, not by the screen: runs live on [agentScope], so a
    // brain built per composition was orphaned when Settings opened mid-run
    // (its pending confirmation vanished with it). Rebuilt for a new config
    // only when nothing is in flight. Main thread only.
    private var chatBrain: com.agent.jarvis.agent.Brain? = null
    private var chatBrainVersion = 0
    private var chatSpeaker: Speaker? = null
    private var chatSpeech: SpeechQueue? = null

    fun chatBrain(
        context: Context,
        engine: com.agent.jarvis.local.LocalModelEngine,
        configVersion: Int,
    ): com.agent.jarvis.agent.Brain {
        val current = chatBrain
        if (current != null &&
            (configVersion == chatBrainVersion || thinking.value || current.pendingConfirm != null)
        ) return current
        val app = context.applicationContext
        val speaker = chatSpeaker ?: Speaker(app).also { chatSpeaker = it }
        val speech = chatSpeech ?: speaker.queue().also { chatSpeech = it }
        return com.agent.jarvis.agent.Brain(app, engine).also { b ->
            // A spoken request is always answered out loud; a typed one when "Speak answers" is on.
            b.speakTyped = { JarvisPrefs.speakAnswers(app) }
            b.onSpeak = { text -> speech.add(text) }
            chatBrain = b
            chatBrainVersion = configVersion
        }
    }
}
