package com.agent.ultra.gate

import org.json.JSONObject

/**
 * Tool manifests (port of gatellml lang/manifest.py). A tool not declared in
 * the manifest is refused at runtime — deny by default.
 */

enum class Effect { READ, MUTATE, EGRESS, RESOLVE, CREATE }

data class ToolSpec(
    val name: String,
    val effects: Set<Effect>,
    val requires: List<Contract>,
    /** Arguments the tool marks optional. Only these may be "absent" when a model
     * passes a stringly null (cc="None"); on a REQUIRED argument "None" is a value. */
    val optionalArgs: Set<String> = emptySet(),
)

class Manifest(private val specs: Map<String, ToolSpec>) {
    fun tool(name: String): ToolSpec? = specs[name]

    /** How many tools are declared. The About screen states this number, and
     * a literal there has gone stale twice (24 → 30 → 31) because adding a
     * tool never reminded anyone to update it. Ask the manifest instead. */
    val size: Int get() = specs.size

    val names: List<String> get() = specs.keys.sorted()

    companion object {
        /**
         * Read the manifest that ships in the APK.
         *
         * An unreadable manifest is not survivable — the gate denies every
         * tool, so returning an empty one is the correct failure. Callers that
         * only want the count get 0, which reads as broken, which it is.
         */
        fun fromAssets(context: android.content.Context): Manifest = try {
            context.assets.open("ultra.manifest.json").bufferedReader().use { r ->
                fromJson(JSONObject(r.readText()))
            }
        } catch (e: Exception) {
            android.util.Log.e("UltraGate", "manifest load failed — gate will deny everything", e)
            fromJson(JSONObject("""{"tools":[]}"""))
        }

        fun fromJson(json: JSONObject): Manifest {
            val tools = json.getJSONArray("tools")
            val map = mutableMapOf<String, ToolSpec>()
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                val name = t.getString("name")
                val effectsArr = t.optJSONArray("effects")
                val effects = mutableSetOf<Effect>()
                if (effectsArr != null) {
                    for (j in 0 until effectsArr.length()) {
                        effects += when (effectsArr.getString(j)) {
                            "read" -> Effect.READ
                            "mutate" -> Effect.MUTATE
                            "egress" -> Effect.EGRESS
                            "resolve" -> Effect.RESOLVE
                            "create" -> Effect.CREATE
                            else -> throw IllegalArgumentException("unknown effect in $name")
                        }
                    }
                }
                val optional = mutableSetOf<String>()
                t.optJSONArray("optional_args")?.let { a -> for (j in 0 until a.length()) optional += a.getString(j) }
                map[name] = ToolSpec(name, effects, contractsFromJson(t.optJSONArray("requires")), optional)
            }
            return Manifest(map)
        }
    }
}
