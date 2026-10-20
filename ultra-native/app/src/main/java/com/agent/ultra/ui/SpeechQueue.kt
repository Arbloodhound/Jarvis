package com.agent.ultra.ui

/**
 * Says texts one after another instead of each new one cutting off the last (Speaker.speak
 * replaces whatever is unfinished). An answer is now spoken sentence by sentence while it is
 * still being written, so the pieces have to queue. Texts that queued up while one was playing
 * go out together, so there is one gap per wait rather than one per sentence.
 *
 * [stop] silences it and drops what is queued; [stopAll] does that for every live queue (the chat,
 * the voice screen and ambient listening each have one), which is what "stop" and a "Hey Jarvis"
 * spoken over Jarvis do.
 *
 * Pure apart from the functions passed in, so the queueing is JVM-tested: [speak] says one text
 * and calls its callback once when done or cut off; [cut] silences what is playing; [post] runs
 * a block on the thread [speak] wants (main, on the phone).
 */
class SpeechQueue(
    private val speak: (String, () -> Unit) -> Unit,
    private val cut: () -> Unit,
    private val post: (() -> Unit) -> Unit = { it() },
) {
    /** A text has started (the echo guard mutes the mic for it). */
    @Volatile var onStart: (String) -> Unit = {}
    /** Everything queued has been said, or [stop] cut it off. */
    @Volatile var onIdle: () -> Unit = {}

    private class Item(val text: String, val after: (() -> Unit)?)

    private val lock = Any()
    private val queue = ArrayDeque<Item>()
    private var speaking = false
    private var gen = 0L

    /** Speaking, or something waiting to be said. */
    val busy: Boolean get() = synchronized(lock) { speaking || queue.isNotEmpty() }

    /** Queue [text]; [after] runs once it has been said in full (not when [stop] cuts it off). */
    fun add(text: String, after: (() -> Unit)? = null) {
        if (text.isBlank()) {
            if (after != null) post(after)
            return
        }
        val start = synchronized(lock) {
            queue.addLast(Item(text.trim(), after))
            if (speaking) null else { speaking = true; takeLocked() to gen }
        } ?: return
        play(start.first, start.second)
    }

    /** Silence now and drop everything queued. */
    fun stop() {
        val was = synchronized(lock) {
            gen++
            queue.clear()
            speaking.also { speaking = false }
        }
        if (was) {
            cut()
            onIdle()
        }
    }

    /** The next text: the head, plus every following item without an [Item.after] (merged). */
    private fun takeLocked(): Item {
        val first = queue.removeFirst()
        if (first.after != null) return first
        val sb = StringBuilder(first.text)
        var after: (() -> Unit)? = null
        while (queue.isNotEmpty()) {
            val n = queue.first()
            sb.append(' ').append(n.text)
            queue.removeFirst()
            if (n.after != null) { after = n.after; break }
        }
        return Item(sb.toString(), after)
    }

    private fun play(item: Item, g: Long) {
        post {
            if (synchronized(lock) { g != gen }) return@post
            onStart(item.text)
            speak(item.text) { done(item, g) }
        }
    }

    private fun done(item: Item, g: Long) {
        val next = synchronized(lock) {
            if (g != gen) return
            if (queue.isEmpty()) { speaking = false; null } else takeLocked()
        }
        item.after?.invoke()
        if (next == null) onIdle() else play(next, g)
    }

    companion object {
        private val live = java.util.concurrent.CopyOnWriteArraySet<SpeechQueue>()

        fun register(q: SpeechQueue): SpeechQueue { live += q; return q }
        fun unregister(q: SpeechQueue) { live -= q }

        /** Jarvis is speaking (or about to) somewhere in the app. */
        val anyBusy: Boolean get() = live.any { it.busy }

        /** Silence every queue. True when something was speaking. */
        fun stopAll(): Boolean {
            var any = false
            for (q in live) { if (q.busy) any = true; q.stop() }
            return any
        }
    }
}
