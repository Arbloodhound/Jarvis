package com.agent.ultra.agent

import com.agent.ultra.ui.ChatStatus

/**
 * Pure, JVM-tested. Where a run was when it went quiet (owner: freezes are not OK, and they want
 * to know WHERE Jarvis goes quiet). When a chat run has shown nothing for [AFTER_MS], or the
 * ambient service's 20 s late cue comes up ([com.agent.ultra.listen.WaitCue]), one line goes into
 * the gate audit log the owner can export ([com.agent.ultra.gate.GateAuditLog.recordQuiet]).
 * Telemetry only: nothing is cancelled or cut short.
 *
 * The stage is read from signals that already exist: whether the run holds the brain's run lock,
 * [Tools.running], the cloud call in progress, and the status line under the chat
 * ([ChatStatus]). Fixed words only: never the user's text or a tool's arguments.
 */
object QuietStage {
    /** Same as the ambient late cue ([com.agent.ultra.listen.WaitCue.LATE_AFTER_MS]). */
    const val AFTER_MS = 20_000L

    const val WAITING_FOR_RUN_LOCK = "waiting-for-run-lock"
    /** A heard command still in the ambient service's queue, behind a run (or a model) still busy. */
    const val QUEUED = "queued-behind-last-command"
    const val LOADING_MODEL = "loading model"
    /** "Thinking…" with no thought shown yet: prompt prefill, a memory search, or a trace with
     *  no preview (spoken runs get none). The status line does not tell these apart. */
    const val PREFILL_OR_THINKING = "prefill-or-thinking"
    const val THINKING = "thinking"
    const val WEB_FETCH = "web fetch"
    const val NAVIGATING = "navigating"
    const val CLOUD_CALL = "cloud call"
    /** Inside the run, none of the signals above up (gate, recipes, memory lookups…). */
    const val WORKING = "working"
    /** No route recorded since the request came in. */
    const val NO_ROUTE = "undecided"

    /**
     * The stage: [entered] the run holds the run lock; [tool] the tool running ([Tools.running]);
     * [cloudCall] a cloud model call is open; [phase]/[detail] the status line under the chat.
     */
    fun stage(entered: Boolean, tool: String?, cloudCall: Boolean, phase: ChatStatus.Phase?, detail: String): String = when {
        !entered -> WAITING_FOR_RUN_LOCK
        tool != null -> "tool:" + toolName(tool)
        cloudCall -> CLOUD_CALL
        phase == ChatStatus.Phase.LOADING -> LOADING_MODEL
        phase == ChatStatus.Phase.NAVIGATING -> NAVIGATING
        // The thought preview ("💭 …") is on: past prefill, in the trace.
        phase == ChatStatus.Phase.THINKING && detail.startsWith("💭") -> THINKING
        // Any other detail on "Thinking…" is the web search before an on-device answer.
        phase == ChatStatus.Phase.THINKING && detail.isNotBlank() -> WEB_FETCH
        phase == ChatStatus.Phase.THINKING -> PREFILL_OR_THINKING
        else -> WORKING
    }

    /** A tool name as the model wrote it, cut to name characters: it never carries other text. */
    fun toolName(tool: String): String =
        tool.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }.take(40).ifEmpty { "?" }

    /** [last] (a "route/reason" recorded at [lastAtMs]) when it was recorded at or after
     *  [sinceMs], the moment the request came in; else [NO_ROUTE]. */
    fun route(last: String?, lastAtMs: Long, sinceMs: Long): String =
        if (last != null && lastAtMs >= sinceMs) last else NO_ROUTE
}
