package com.agent.jarvis.agent

import com.agent.jarvis.harness.PromptFragment
import com.agent.jarvis.harness.SessionEnvelope
import com.agent.jarvis.harness.reusablePrefixLen

/**
 * Glue between the on-device prompts and harness/PromptEnvelope. Pure, JVM-tested.
 *
 * The system block is assembled from four fragments in a FIXED order — core_persona,
 * user_profile, loaded_tool_schemas, covered_history — so everything that changes often
 * (loading a tool namespace, new history) sits after everything that rarely does. The native
 * engine keeps the KV of the longest common TOKEN prefix between consecutive prompts
 * (cpp/jarvis_llm.cpp nativeGenerate); a stable fragment order is what makes that prefix long.
 *
 * covered_history is the ChatML turn text that follows the system block; it is a fragment of
 * the prompt prefix even though it is not inside the system message. The late "Now / memory"
 * block and the user turn are volatile and not fragments.
 *
 * [reusableFragments] is a fragment-level estimate of what the native token-level reuse can
 * keep; it is not a measurement of tokens actually reused (that is the native "reused=N of M"
 * log line).
 */
object LocalEnvelope {
    const val TOTAL = 4

    fun fragments(persona: String, profile: String, tools: String, history: String): List<PromptFragment> = listOf(
        PromptFragment(PromptFragment.CORE_PERSONA, persona),
        PromptFragment(PromptFragment.USER_PROFILE, profile),
        PromptFragment(PromptFragment.LOADED_TOOL_SCHEMAS, tools),
        PromptFragment(PromptFragment.COVERED_HISTORY, history),
    )

    /** The system message text: the non-empty fragments before covered_history, joined by [sep]. */
    fun systemText(fragments: List<PromptFragment>, sep: String): String =
        fragments.filter { it.id != PromptFragment.COVERED_HISTORY && it.text.isNotEmpty() }
            .joinToString(sep) { it.text }

    /** The direct-answer prompt (no tools): same system text as [LocalPrompt.offlineSystem]. */
    fun answer(ownerName: String, instructions: String, history: List<LocalPrompt.Turn>, historyChars: Int): SessionEnvelope =
        SessionEnvelope(fragments(
            LocalPrompt.OFFLINE_RULES,
            LocalPrompt.ownerSection(ownerName, instructions),
            "",
            LocalPrompt.renderHistory(LocalPrompt.keptHistory(history, historyChars)),
        ))

    /** Leading fragments identical to the previous prompt's; 0 when there is none yet. */
    fun reusableFragments(old: SessionEnvelope?, new: SessionEnvelope): Int =
        if (old == null) 0 else reusablePrefixLen(old.fragments, new.fragments)
}
