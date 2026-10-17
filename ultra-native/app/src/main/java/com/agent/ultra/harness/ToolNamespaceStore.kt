package com.agent.ultra.harness

/**
 * Deferred tool loading. The prompt keeps the core tools' full schemas at all
 * times, but every other namespace starts as a one-line stub; its full parameter
 * schemas enter the prompt only after the agent calls
 * `load_tool_namespace("name")`. This keeps the tool section small until a
 * capability is actually needed, which in turn keeps the KV prefix stable (see
 * [PromptEnvelope]).
 *
 * This is about TOOL namespaces, not SKILL.md (that is [SkillStore]).
 *
 * Pure: the namespace → full-schema map is injected, so the JVM suite checks that
 * a namespace shows only its stub before [load] and its full schema after.
 *
 * Wire-in note (NOT done here): the full tool catalogue today is the static
 * `TOOL_CATALOG` string in agent/Brain.kt. The clean hook would be to render the
 * tool section via [promptBlock] with that catalogue split into namespaces, so
 * unused namespaces cost one line each. That rewire is non-trivial (Brain builds
 * one prompt string) and is deliberately left to a focused change with its own
 * tests.
 */
class ToolNamespaceStore(
    /** namespace → its full schema block (core namespaces excluded). */
    private val schemas: Map<String, String>,
) {
    private val active = LinkedHashSet<String>()

    /**
     * Mark [namespace] active so [promptBlock] renders its full schema.
     * @return true if it exists and was newly loaded; false if unknown or already active.
     */
    fun load(namespace: String): Boolean {
        if (namespace in CORE) return false
        if (namespace !in schemas) return false
        return active.add(namespace)
    }

    fun isActive(namespace: String): Boolean = namespace in active

    fun activeNamespaces(): Set<String> = active.toSet()

    /** core full schemas + active full schemas + one-line stubs for the rest. */
    fun promptBlock(): String {
        val sb = StringBuilder()
        sb.append(CORE_BLOCK)
        for (ns in schemas.keys) {
            if (ns in active) {
                sb.append('\n').append(schemas.getValue(ns).trimEnd())
            } else {
                sb.append('\n').append(stub(ns))
            }
        }
        return sb.toString()
    }

    private fun stub(ns: String) =
        "$ns: [deferred: use load_tool_namespace(\"$ns\") to load full parameter schemas]"

    companion object {
        /** Always loaded in full; never deferred. */
        val CORE = linkedSetOf(
            "read", "write", "edit", "exec", "memory_search",
            "load_tool_namespace", "finish_turn",
        )

        /** Namespaces that are deferred by default (injected schemas key on these). */
        val DEFERRABLE = linkedSetOf(
            "shopping", "products", "tor", "investigate", "browse", "device", "vision",
        )

        private val CORE_BLOCK: String = CORE.joinToString("\n") { "$it: [core tool, always available]" }
    }
}
