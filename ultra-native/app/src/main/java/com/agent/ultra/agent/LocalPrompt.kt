package com.agent.ultra.agent

/**
 * Prompt for the on-device Qwen3 Thinking model (ChatML). Pure Kotlin, JVM-tested.
 *
 * The cloud brain gets the full system prompt + tool loop. Offline, the big local
 * model answers directly, so it gets the reasoning rules that matter without
 * tools (grounding, novelty, verification, provenance; system-prompt rules 13-16),
 * and nothing that names tools it can't call.
 */
object LocalPrompt {

    val OFFLINE_RULES = """
You are Ultra, a private assistant running entirely on this phone, offline. You cannot browse or use tools in this mode.
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
     */
    fun build(system: String, history: List<Turn>, user: String, historyChars: Int = 3000): String {
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
            append("<|im_start|>user\n").append(user.trim()).append("<|im_end|>\n")
            append("<|im_start|>assistant\n<think>\n")
        }
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
