package com.agent.jarvis.harness

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
 * Wire-in: the on-device tool loop (agent/LocalToolCall + Brain.thinkingToolCall) builds its
 * tool section from one of these per Brain session. The cloud brain's static `TOOL_CATALOG`
 * in agent/Brain.kt is NOT routed through it.
 */
class ToolNamespaceStore(
    /** namespace → its full schema block (core namespaces excluded). */
    private val schemas: Map<String, String>,
    /** The always-full block. Defaults to the generic core list; the on-device loop passes its own. */
    private val coreBlock: String = CORE_BLOCK,
    /** Names that can never be loaded/deferred. */
    private val core: Set<String> = CORE,
) {
    private val active = LinkedHashSet<String>()

    /**
     * Mark [namespace] active so [promptBlock] renders its full schema.
     * @return true if it exists and was newly loaded; false if unknown or already active.
     */
    fun load(namespace: String): Boolean {
        if (namespace in core) return false
        if (namespace !in schemas) return false
        return active.add(namespace)
    }

    /** True when at least one namespace is deferrable (so the model has something to load). */
    val hasDeferred: Boolean get() = schemas.isNotEmpty()

    fun isActive(namespace: String): Boolean = namespace in active

    fun activeNamespaces(): Set<String> = active.toSet()

    /** core full schemas + active full schemas + one-line stubs for the rest. */
    fun promptBlock(): String {
        val sb = StringBuilder()
        sb.append(coreBlock)
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
