package com.agent.ultra.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
 * word, or a request that refers back. A fresh question gets no brief — unrelated text in
 * the prompt is what the small-quant model copies from (DEVLOG 2026-10-06).
 */
object MemoryBrief {

    @Volatile var provider: (suspend (query: String) -> String?)? = null

    const val HEADER = "What I remember (from listening and notes) — may be incomplete:"
    const val MAX_CHARS = 800
    /** A search that takes longer than this is skipped; the answer goes ahead without it. */
    const val TIMEOUT_MS = 4000L

    data class Decision(val search: Boolean, val reason: String)

    private fun rx(vararg words: String) =
        Regex("\\b(" + words.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    private val whoSaid = rx(
        "what did (i|he|she|they|we|you|\\w+) (say|mention|tell|ask|decide|agree|want|promise|think)",
        "who (said|mentioned|told|asked|called|texted|was talking)",
        "when (did|was|were) (i|he|she|they|we|\\w+) (say|said|mention|tell|told|ask|call|text|meet|met|talk)",
        "did (i|he|she|they|we|\\w+) (say|mention|tell|ask|call|text)",
        "(said|mentioned|told me|talked|was talking) about",
        "what was (that|it|the \\w+) about", "do you remember", "remember (when|what|who|where|that|the|my)",
        "recall", "remind me (what|who|when|where|about|of|how|which|why)",
        "where did i (put|leave|park)", "my notes?", "(the|a) note (about|on|for)",
    )
    private val remindMeBare = Regex("\\bremind me\\W*$", RegexOption.IGNORE_CASE)
    private val pastTime = rx(
        "yesterday", "this (morning|afternoon|evening)", "earlier( today)?", "last (night|week|month|time|" +
            "monday|tuesday|wednesday|thursday|friday|saturday|sunday)",
        "the other day", "(a|an|\\d+|few|couple of) (minutes?|hours?|days?|weeks?) ago", "just now",
    )
    // A name (capitalised, not the first word) next to a speech or contact word:
    // "what's Sarah's number", "call with Tom", "Dana said". "who was Napoleon" is not.
    private val nameNearSpeech = Regex(
        "(\\b(with|from|tell|ask|call|text|email|meet|met)\\s+[A-Z][a-z]+\\b)|" +
            "(\\b[A-Z][a-z]+(['’]s|\\s+(said|says|told|asked|mentioned|wants|wanted|thinks|thought|called|texted|emailed))\\b)",
    )

    fun wants(request: String): Decision {
        val full = IntentRouter.requestPart(request).trim()
        if (full.length > HistoryPolicy.LONG_PASTE_CHARS) return Decision(false, "long paste: self-contained")
        val t = HistoryPolicy.instructionPart(request)
        if (t.isEmpty()) return Decision(false, "empty")
        // The first word is capitalised by the keyboard, not because it is a name.
        val afterFirst = t.substringAfter(' ', "")
        return when {
            whoSaid.containsMatchIn(t) || remindMeBare.containsMatchIn(t) -> Decision(true, "asks what was said or noted")
            pastTime.containsMatchIn(t) -> Decision(true, "past time")
            nameNearSpeech.containsMatchIn(afterFirst) -> Decision(true, "a person's name")
            HistoryPolicy.decide(request).include -> Decision(true, "refers back")
            else -> Decision(false, "no memory cue")
        }
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
    suspend fun recall(query: String, timeoutMs: Long = TIMEOUT_MS): String? {
        val p = provider ?: return null
        val text = try {
            withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.IO) { p(query) } }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()   // the caller was cancelled: propagate
            null                                       // the provider's own timeout: no brief
        } catch (_: Exception) {
            null
        }
        return text?.takeIf { it.isNotBlank() }?.let { cap(it) }
    }
}
