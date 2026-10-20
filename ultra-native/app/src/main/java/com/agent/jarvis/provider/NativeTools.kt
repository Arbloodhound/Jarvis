package com.agent.jarvis.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native (OpenAI-style) tool calling for the cloud brain, as an adapter: the tool list goes in the
 * request's "tools", and a streamed tool call comes back as the brain's existing text format
 * ({"tool":"name","params":{...}}), so the loop, its history and its parser are unchanged. The
 * provider fills the arguments as JSON itself, instead of the model writing JSON into prose.
 * Pure, JVM-tested.
 */
object NativeTools {

    /**
     * One function per tool: its catalog line as the description, parameters left open
     * (the system prompt's catalog still documents them).
     */
    fun definitions(names: Collection<String>, catalog: String): JSONArray {
        val lines = catalog.lines()
        val out = JSONArray()
        for (n in names.sorted()) {
            val line = lines.firstOrNull { Regex("(?<![a-z_])${Regex.escape(n)}(?![a-z_])").containsMatchIn(it) }
                ?.trim()?.trimStart('-', ' ')?.take(300) ?: n
            out.put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", n)
                .put("description", line)
                .put("parameters", JSONObject().put("type", "object").put("additionalProperties", true))))
        }
        return out
    }

    /** Pure. Does a provider error say it doesn't take "tools"? */
    fun rejectedTools(error: String): Boolean {
        val e = error.lowercase()
        return e.startsWith("http 4") && ("tool" in e || "function" in e)
    }

    /** Streamed tool_calls deltas, put back together (index → name + argument text). */
    class CallAccumulator {
        private val names = sortedMapOf<Int, StringBuilder>()
        private val args = sortedMapOf<Int, StringBuilder>()

        fun add(deltas: JSONArray) {
            for (i in 0 until deltas.length()) {
                val d = deltas.optJSONObject(i) ?: continue
                val idx = d.optInt("index", 0)
                val f = d.optJSONObject("function") ?: continue
                if (f.has("name") && !f.isNull("name")) names.getOrPut(idx) { StringBuilder() }.append(f.optString("name"))
                if (f.has("arguments") && !f.isNull("arguments")) args.getOrPut(idx) { StringBuilder() }.append(f.optString("arguments"))
            }
        }

        /** The first call as {"tool":…,"params":…}; null when there was none. Unparseable arguments → {}. */
        fun asTextCall(): String? {
            val idx = names.keys.firstOrNull() ?: return null
            val name = names[idx].toString().trim().ifBlank { return null }
            val raw = args[idx]?.toString()?.trim().orEmpty()
            val params = try { if (raw.isEmpty()) JSONObject() else JSONObject(raw) } catch (_: Exception) { JSONObject() }
            return JSONObject().put("tool", name).put("params", params).toString()
        }
    }
}
