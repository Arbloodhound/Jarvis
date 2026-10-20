package com.agent.jarvis.bench

/**
 * The cloud stand-in diagnostic (Jarvis Tests): the cloud model running the on-device brain's exact
 * system prompt and tools, so it can see what the local model sees and report what works, fails, or
 * is limited. Pure, no Android, so the JVM tests reach it (like [CloudBrainReport]); the debug-only
 * TestKitActivity drives the live run.
 *
 * The point of the word "muted" (owner, 2026-10-17): the on-device brain's generation gets cut off
 * by the real run — the per-turn token budget, a spoken interrupt, the memory/action model taking
 * the shared engine. The stand-in is NOT cut. At the exact point it would be cut it is muted (the
 * text past that point is dropped from what the run acts on) but it keeps generating, and it is told
 * it was muted and why — so it stays aware and writes that cutoff into its diagnostic.
 *
 * Over an HTTP completion we can't pause mid-token on the server, so the cut is emulated per turn:
 * run the turn, find where the on-device budget would have cut it ([muteBoundary]), keep the whole
 * reply but mark the part past the boundary as muted, and inject [muteNotice] as the next message.
 *
 * Nothing here is about onion routing, Tor, or any model's training: it is a token-cutoff emulator
 * and a tool-call ledger, and [CloudStandInTest] asserts the injected text stays that way.
 */
object CloudStandIn {

    /** Why the on-device brain would have cut the stream at this point. */
    enum class Reason { BUDGET, LENGTH, INTERRUPT, ENGINE_CONTENTION }

    data class MuteEvent(val turn: Int, val reason: Reason, val keptTokens: Int, val mutedTokens: Int)

    /** The agentic run's cap. Mirror of the loop in Brain.runLoop. */
    const val MAX_TURNS = 12

    /**
     * The on-device brain's per-turn token budget, mirrored from Brain.runLoop (Brain.kt:1261):
     * a wide first turn, a wide last couple of turns to finish, a tighter middle. Kept in sync by
     * [CloudStandInTest].
     */
    fun perTurnBudget(turn: Int, maxTurns: Int = MAX_TURNS): Int = when {
        turn == 0 -> 2000
        turn >= maxTurns - 2 -> 2500
        else -> 1500
    }

    /**
     * The character index where the on-device brain's [budgetTokens] would have cut a reply of
     * [replyChars], or null when the reply fits. Tokens are the same ~4-chars estimate the report
     * uses ([CloudBrainReport.approxTokens]), so the boundary matches what the check would predict.
     */
    fun muteBoundary(replyChars: Int, budgetTokens: Int): Int? {
        if (replyChars <= 0 || budgetTokens <= 0) return null
        val budgetChars = budgetTokens * 4
        return if (replyChars > budgetChars) budgetChars else null
    }

    /** The message handed back to the model after a mute: it stays aware and notes the cutoff. */
    fun muteNotice(e: MuteEvent): String {
        val why = when (e.reason) {
            Reason.BUDGET -> "the on-device brain's per-turn token budget ran out"
            Reason.LENGTH -> "the model itself stopped at the token limit"
            Reason.INTERRUPT -> "a spoken interrupt would have stopped the on-device brain"
            Reason.ENGINE_CONTENTION -> "the memory or action model would have taken the shared engine"
        }
        return "⟨muted at turn ${e.turn}: on the phone the brain would have been CUT here — $why " +
            "(kept ~${e.keptTokens} tokens, dropped ~${e.mutedTokens}). You were muted, not cut: keep going, " +
            "and record this cutoff point in your diagnostic.⟩"
    }

    /** The reply with its muted tail removed, when [boundary] is non-null. */
    fun applyMute(reply: String, boundary: Int?): String =
        if (boundary == null || boundary >= reply.length) reply else reply.substring(0, boundary)

    /** How a dry-run tool call turned out, for the ledger: it is logged, never executed. */
    enum class ToolOutcome { SUCCESS, FAILED, LIMITED }

    /**
     * Classify a tool call without running it: a tool whose access is off right now would be LIMITED
     * (the model is told it has it, but it can't work); an unknown tool name would FAIL; otherwise it
     * would SUCCEED. [deadTools] is [CloudBrainReport.deadTools] for the current access.
     */
    fun classify(tool: String, manifest: Set<String>, deadTools: Set<String>): ToolOutcome = when {
        tool !in manifest -> ToolOutcome.FAILED
        tool in deadTools -> ToolOutcome.LIMITED
        else -> ToolOutcome.SUCCESS
    }

    /** The diagnostic instruction, appended after the real system prompt. */
    const val PROMPT = """DIAGNOSTIC RUN from the owner's test app. You are running on the on-device brain's
exact system prompt and exact tools (above). This is a diagnostic: you may call tools, but they are
NOT executed — each call is logged with whether it would succeed, fail, or be limited, and a
stand-in result is returned so you can continue.

You will be MUTED, never cut. On the phone the brain's output is cut off at points — the per-turn
token budget, a spoken interrupt, the memory or action model taking the shared engine. Here you are
not cut: when you reach such a point you are told you were muted and why, and you keep generating.

Your job: keep high vigilance on every tool call — note each one as SUCCESS, FAILED, or LIMITED
(offered to you but its access is off) — and on every mute. Then write a full diagnostic: what you
can and can't do, where you were muted and what you would have lost there, and anything in your
instructions that is unclear or contradicts itself."""
}
