package com.agent.ultra.agent

/**
 * Prompt for the on-device Qwen3 Thinking model (ChatML). Pure Kotlin, JVM-tested.
 *
 * The cloud brain gets the full system prompt + tool loop. Offline, the big local
 * model answers directly: it gets the reasoning rules (grounding, novelty, verification,
 * provenance; system-prompt rules 13-16) and a true account of what Jarvis can do around it
 * (memory arrives as a "What I remember" block; phone actions are run by the app's own tool
 * step, not from this reply). It used to be told only "you cannot use tools", so asked about
 * its memory or notes it said it had none (owner, 2026-10-07).
 */
object LocalPrompt {

    val OFFLINE_RULES = """
You are Jarvis, a private assistant running entirely on this phone, offline. What you can do (all true; never deny it):
- When ambient listening is on, you listen in the background and remember what is said near the phone. While the phone charges, a smaller memory model sorts that into notes (people, plans, to-dos).
- When a question is about the past or about what you remember, the app searches that memory and gives you what it found in a "What I remember" block. Use it, with its times. If the block says nothing was found, say you have nothing on that in your memory. Never say you have no memory.
- The app carries out phone actions when the user asks in plain words: flashlight, Wi-Fi, Bluetooth, Do Not Disturb, volume, alarms, notes, opening apps, battery level, clipboard, and simple steps inside apps. You can't run them from this reply: if the user wants one, tell them to just ask for it, e.g. "turn on the flashlight".
- The current date and time are in the "Now" line. You cannot browse the web in this mode.
How to answer:
- Reason from your own knowledge and answer. Never stall or apologize for lacking the web.
- If the user describes their own work or a new idea, reason it through from first principles. Not knowing about it is not evidence they are wrong.
- For non-trivial factual or technical claims, check the premises that would have to be true, then give the conclusion and how confident you are. Keep that concise.
- In pharmacology, chemistry, opsec and medical or psychological answers, add "(from model knowledge, unverified)". This is a tag, never a refusal.
- Your reply may be spoken aloud: lead with the answer, keep it short unless asked for depth.
""".trim()

    data class Turn(val fromUser: Boolean, val text: String)

    /**
     * ChatML prompt ending in an opened <think> block, exactly like the model's own
     * template with add_generation_prompt=true. Pass startsInThink=true to ThinkBudget.
     * History turns are stripped of reasoning; oldest are dropped past [historyChars].
     * A non-blank [memory] ([MemoryBrief]) and [now] (the date and time, "Now: …") go in one
     * late system block after the history and before the user turn, so the system + history
     * prefix stays the same (and reusable in the KV cache) whatever the time or the brief.
     */
    fun build(
        system: String, history: List<Turn>, user: String, historyChars: Int = 3000, memory: String? = null,
        now: String? = null, memoryCap: Int = MemoryBrief.MAX_CHARS, memoryHeader: String = MemoryBrief.HEADER,
    ): String {
        val kept = ArrayDeque<Turn>(); var used = 0
        for (t in history.asReversed()) {
            val text = if (t.fromUser) t.text.trim() else ThinkBudget.stripForHistory(t.text)
            if (text.isEmpty()) continue
            if (used + text.length > historyChars) break
            kept.addFirst(Turn(t.fromUser, text)); used += text.length
        }
        return buildString {
            append("<|im_start|>system\n").append(system.trim()).append("<|im_end|>\n")
            for (t in kept) {
                append("<|im_start|>").append(if (t.fromUser) "user" else "assistant").append('\n')
                append(t.text).append("<|im_end|>\n")
            }
            if (!memory.isNullOrBlank() || !now.isNullOrBlank()) {
                append("<|im_start|>system\n")
                if (!now.isNullOrBlank()) append("Now: ").append(now.trim()).append('\n')
                if (!memory.isNullOrBlank()) append(memoryHeader).append('\n').append(MemoryBrief.cap(memory, memoryCap)).append('\n')
                setLength(length - 1)
                append("<|im_end|>\n")
            }
            append("<|im_start|>user\n").append(user.trim()).append("<|im_end|>\n")
            append("<|im_start|>assistant\n<think>\n")
        }
    }

    /** The "Now" line's text: "Tuesday 7 October 2026, 2:05 PM". */
    fun nowLine(ms: Long = System.currentTimeMillis(), zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        java.text.SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", java.util.Locale.UK).apply { timeZone = zone }.format(java.util.Date(ms))

    /** Qwen3's soft switch: in the user turn it asks for an answer without reasoning. */
    const val NO_THINK = "/no_think"

    /**
     * ChatML prompt for a plain answer without reasoning (the memory model's searches and
     * sorting): the user turn ends in [NO_THINK], and the assistant turn starts with the empty
     * think block Qwen3's own template emits with thinking off, so decoding begins at the answer.
     * No history: each call stands alone. Do not pass the result to a ThinkBudget.
     */
    fun buildNoThink(system: String, user: String): String = buildString {
        append("<|im_start|>system\n").append(system.trim()).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(user.trim()).append(' ').append(NO_THINK).append("<|im_end|>\n")
        append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
    }

    /** No tokenizer on the Kotlin side: ~3 chars/token is a safe over-estimate for English. */
    fun estimateTokens(prompt: String): Int = prompt.length / 3 + 1

    /** Answer room after the trace; smaller when the phone is hot. */
    fun answerReserve(level: ThermalPolicy.Level): Int =
        if (level >= ThermalPolicy.Level.HOT) 256 else 512

    /** History without the turn being answered now — LocalPrompt.build adds it once.
     * It is not always last: after a cloud failure a "(cloud unreachable…)" note follows it. */
    fun withoutCurrentTurn(history: List<Turn>, userInput: String): List<Turn> {
        val i = history.indexOfLast { it.fromUser && it.text.trim() == userInput.trim() }
        return if (i < 0) history else history.filterIndexed { j, _ -> j != i }
    }
}
