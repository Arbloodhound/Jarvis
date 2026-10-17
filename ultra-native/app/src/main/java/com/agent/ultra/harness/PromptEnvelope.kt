package com.agent.ultra.harness

import java.security.MessageDigest

/**
 * Prompt envelope — the system prompt split into stable, ordered fragments so a
 * session can reuse the KV cache of the leading fragments that did not change.
 *
 * Each [PromptFragment] carries the SHA-256 of its text. Across two turns, the
 * longest run of leading fragments with identical hashes is the pinnable prefix:
 * its KV state can be kept and only the diverging tail re-encoded.
 *
 * Fragment order is fixed: [CORE_PERSONA], [USER_PROFILE], [LOADED_TOOL_SCHEMAS],
 * [COVERED_HISTORY]. Tool-schema churn (deferred namespaces loading) therefore
 * invalidates history re-use but not the persona/profile prefix.
 *
 * Device integration point (NOT done here): the llama.cpp / LiteRT KV-pin wire-in
 * takes [reusablePrefixLen] plus the per-fragment token counts and tells the
 * runtime how many leading tokens to keep pinned. This file only computes the
 * fragment-level reusable prefix; it does not touch the inference runtime.
 */
data class PromptFragment(
    val id: String,
    val text: String,
    val sha256: String = sha256Hex(text),
) {
    companion object {
        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xff
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
            }
            return sb.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()

        const val CORE_PERSONA = "core_persona"
        const val USER_PROFILE = "user_profile"
        const val LOADED_TOOL_SCHEMAS = "loaded_tool_schemas"
        const val COVERED_HISTORY = "covered_history"
    }
}

/**
 * One session's prompt: its ordered fragments plus the fingerprints of the tools
 * whose schemas are loaded. The fingerprints let the caller tell whether the
 * tool surface changed even when [PromptFragment.LOADED_TOOL_SCHEMAS] is rendered
 * the same way.
 */
data class SessionEnvelope(
    val fragments: List<PromptFragment>,
    val toolFingerprints: List<String> = emptyList(),
)

/**
 * Count of leading fragments identical by [PromptFragment.sha256] between [old]
 * and [new] — the fragments whose KV cache can be pinned and reused. The first
 * differing (or missing) fragment ends the prefix.
 */
fun reusablePrefixLen(old: List<PromptFragment>, new: List<PromptFragment>): Int {
    val n = minOf(old.size, new.size)
    var i = 0
    while (i < n && old[i].sha256 == new[i].sha256) i++
    return i
}
