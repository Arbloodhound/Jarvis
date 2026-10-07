package com.agent.ultra.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the offline brain is told it remembers. Pure Kotlin, JVM-tested.
 *
 * [provider] is the hook: given the request, it returns a short text of what memory search
 * found (overheard speech, notes), or null/blank for nothing. It is set from outside (the
 * memory search lives elsewhere); while it is null the offline prompt is exactly as before.
 * [LocalPrompt.build] puts a non-blank brief in its own labelled block just before the user
 * turn, after the history, so the stable system + history prefix stays reusable in the KV cache.
 *
 * Only requests that plausibly need memory search it ([wants]): who said what or when,
 * "remind me", "do you remember", past-time words, a person's name with a speech/contact
 * word. A request that only refers back to the chat gets history instead. A fresh question gets no brief — unrelated text in
 * the prompt is what the small-quant model copies from (DEVLOG 2026-10-06).
 */
object MemoryBrief {

    /** (query, nowMs) -> brief text; set by the memory search (MemoryRecall). Null: no brief. */
    @Volatile var provider: (suspend (query: String, nowMs: Long) -> String?)? = null

    /** (nowMs) -> the newest notes and heard lines, for "what do you remember?" ([Decision.overview]):
     *  a search on those words finds nothing useful. Set beside [provider]. */
    @Volatile var overviewProvider: (suspend (nowMs: Long) -> String?)? = null

    /** The brief when memory was searched and nothing came back: the model is told so, rather
     *  than getting no block and answering that it has no memory. */
    const val NOTHING_FOUND = "Nothing on this was found in what I heard or in my notes."

    const val HEADER = "What I remember (from listening and notes) — may be incomplete:"
    const val MAX_CHARS = 600
    /** A search that takes longer than this is skipped; the answer goes ahead without it. */
    const val TIMEOUT_MS = 4000L

    /** [overview]: the request asks what Jarvis remembers in general ("what do you remember?",
     *  "what notes do you have?"): answer it from the newest memory, not a search. */
    data class Decision(val search: Boolean, val reason: String, val overview: Boolean = false)

    /**
     * Questions about Jarvis's memory itself, matched whole (lowercased, punctuation and a
     * leading "hey jarvis" / "jarvis" removed). None of them matched any rule below, so the
     * brain got no memory and said it had none (owner, 2026-10-07).
     */
    private val OVERVIEW = listOf(
        "(what|anything) (do|can|did) you (remember|recall)( (about me|about us|from today|from (this|last) week|recently|lately|so far))?",
        "what do you know about (me|us|my life)",
        "what (have|did) you (hear|heard|overhear|overheard|notice|noticed|learn|learned|learnt|pick up|picked up)" +
            "( (today|recently|lately|so far|about me|this week))?",
        "(what s|what is|whats) in your (memory|memories|notes)",
        "(read me|show me|tell me|what are|list) (your|my|the) (notes|memories)",
        "do you have (a |any )?(memory|memories|notes)( (of|about) (me|us))?",
        "(can|do) you remember (things|stuff|anything|me|what i said|what we talked about)",
    ).map { Regex(it) }

    private val leadIn = Regex("^(?:(?:hey|ok|okay|hi) )?(?:jarvis )?")

    /** True for a question about Jarvis's memory as a whole ([OVERVIEW]). Pure. */
    fun isOverview(request: String): Boolean {
        val t = HistoryPolicy.instructionPart(request).lowercase().replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ").trim().replace(leadIn, "").trim()
        return OVERVIEW.any { it.matches(t) }
    }

    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun rx(vararg words: String) =
        Regex("\\b(" + words.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    private val whoSaid = rx(
        "what did (i|he|she|they|we|you) (say|mention|tell|ask|decide|agree|want|promise|think)",
        "what did \\w+ (say|mention|tell|ask|promise)",
        "who (said|mentioned|told|asked|called|texted|was talking)",
        "when (did|was|were) (i|he|she|they|we|\\w+) (say|said|mention|tell|told|ask|call|text|meet|met|talk)",
        "did (i|he|she|they|we|\\w+) (say|mention|tell|ask|call|text)",
        "(said|mentioned|told me|talked|was talking) about",
        "what was (that|it|the \\w+) about", "do you remember", "remember (when|what|who|where|that|the|my)",
        "(do|can|could) you recall", "recall (when|what|who|where|that|my|the (name|time|place|day))",
        "(the|my|our|that) (meeting|call|conversation) (with|about|on|at|this|yesterday|last|earlier)",
        "remind me (what|who|when|where|about|of|how|which|why)",
        "where did i (put|leave|park)", "my notes?", "(the|a) note (about|on|for)",
    )
    private val remindMeBare = Regex("\\bremind me\\W*$", RegexOption.IGNORE_CASE)
    private val pastTime = rx(
        "yesterday", "this (morning|afternoon|evening)", "earlier (today|this \\w+)",
        "(said|mentioned|heard|told me|talked about|discussed|decided|agreed) earlier", "last (night|week|month|time|" +
            "monday|tuesday|wednesday|thursday|friday|saturday|sunday)",
        "the other day", "(a|an|\\d+|few|couple of) (minutes?|hours?|days?|weeks?) ago", "just now",
    )
    // A name (capitalised, not the first word) next to a speech or contact word:
    // "what's Sarah's number", "call with Tom", "Dana said". "who was Napoleon",
    // "Newton's second law" and "help me with Python" are not: a possessive counts only
    // before a contact word, "with/from" only after a call/meeting/message word.
    private val nameNearSpeech = Regex(
        "(\\b(tell|ask|call|text|email|meet|met|(call|meeting|chat|talk|conversation|message|text|email|notes?) (with|from))\\s+[A-Z][a-z]+\\b)|" +
            "(\\b[A-Z][a-z]+['’]s\\s+(number|phone|address|email|birthday|wife|husband|kids?|plan|idea|news|message|text|call)\\b)|" +
            "(\\b[A-Z][a-z]+\\s+(said|says|told|asked|mentioned|wants|wanted|thinks|thought|called|texted|emailed)\\b)",
    )

    fun wants(request: String): Decision {
        val full = IntentRouter.requestPart(request).trim()
        if (full.length > HistoryPolicy.LONG_PASTE_CHARS) return Decision(false, "long paste: self-contained")
        val t = HistoryPolicy.instructionPart(request)
        if (t.isEmpty()) return Decision(false, "empty")
        // The first word is capitalised by the keyboard, not because it is a name.
        val afterFirst = t.substringAfter(' ', "")
        return when {
            isOverview(t) -> Decision(true, "asks what Jarvis remembers", overview = true)
            whoSaid.containsMatchIn(t) || remindMeBare.containsMatchIn(t) -> Decision(true, "asks what was said or noted")
            pastTime.containsMatchIn(t) -> Decision(true, "past time")
            nameNearSpeech.containsMatchIn(afterFirst) -> Decision(true, "a person's name")
            // A request that only refers back to the chat ("tell me more") gets history, not memory.
            else -> Decision(false, "no memory cue")
        }
    }

    /** True when the request plausibly needs memory search ([wants]). */
    fun needsMemory(request: String): Boolean = wants(request).search

    private val vaguePronoun = Regex("\\b(that|it|this|those|these|them|he|she|they|him|her)\\b", RegexOption.IGNORE_CASE)

    /**
     * The search text for [request]: its instruction part, plus [previousUserTurn] when the
     * instruction leans on a pronoun ("what did he say about that") and so has little content
     * of its own. The previous turn is capped so it cannot drown the request.
     */
    fun query(request: String, previousUserTurn: String?): String {
        val t = HistoryPolicy.instructionPart(request)
        val prev = previousUserTurn?.let { HistoryPolicy.instructionPart(it) }?.trim().orEmpty()
        if (prev.isEmpty() || prev == t || !vaguePronoun.containsMatchIn(t)) return t
        return "$t (about: ${prev.take(200)})"
    }

    /** At most [max] chars, cut at a line or word boundary when one is near. */
    fun cap(text: String, max: Int = MAX_CHARS): String {
        val t = text.trim()
        if (t.length <= max) return t
        val head = t.substring(0, max - 1)
        val cut = maxOf(head.lastIndexOf('\n'), head.lastIndexOf(' '))
        return (if (cut >= max / 2) head.substring(0, cut) else head).trimEnd() + "…"
    }

    /**
     * The brief for [query], capped, or null when there is no provider, it found nothing,
     * failed, or took longer than [timeoutMs] (the provider must be cancellable for the
     * timeout to cut it short). Never throws, except when the caller itself is cancelled.
     */
    suspend fun recall(query: String, nowMs: Long = System.currentTimeMillis(), timeoutMs: Long = TIMEOUT_MS): String? {
        val p = provider ?: return null
        return bounded(timeoutMs) { p(query, nowMs) }
    }

    /** The newest memory ([overviewProvider]), bounded like [recall]. */
    suspend fun recallOverview(nowMs: Long = System.currentTimeMillis(), timeoutMs: Long = TIMEOUT_MS): String? {
        val p = overviewProvider ?: return null
        return bounded(timeoutMs) { p(nowMs) }
    }

    private suspend fun bounded(timeoutMs: Long, search: suspend () -> String?): String? {
        // Detached, so a provider that blocks without checking cancellation cannot hold the
        // answer past the timeout; it finishes (and is dropped) in the background.
        val job = searchScope.async { search() }
        val text = try {
            withTimeoutOrNull(timeoutMs) { job.await() }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()   // the caller was cancelled: propagate
            null                                       // the provider's own timeout: no brief
        } catch (_: Exception) {
            null
        } finally {
            if (!job.isCompleted) job.cancel()
        }
        return text?.takeIf { it.isNotBlank() }?.let { cap(it) }
    }
}
