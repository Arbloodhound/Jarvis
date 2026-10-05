package com.agent.ultra.gate

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs gatellml's shared vector file (gatellml/tests/vectors/gate_vectors.json)
 * against this port. The Python runtime runs the same file. A fix that lands in
 * one port only — substring tracing did, twice — fails here instead of waiting
 * for an audit. Sync the resource from gatellml; never edit it in place.
 */
class SharedVectorsTest {
    private val SUPPORTED = setOf<String>()

    @Test
    fun everyVectorAgreesWithThePythonRuntime() {
        val text = javaClass.classLoader!!.getResource("gate_vectors.json")!!.readText()
        val vectors = JSONObject(text).getJSONArray("vectors")
        val wrong = mutableListOf<String>()
        var skipped = 0
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            // A vector may need a feature this port does not have (the Created origin:
            // phone tools return no object ids). Skip it, and say how many were skipped.
            val needs = v.optJSONArray("needs")
            if (needs != null && (0 until needs.length()).any { needs.getString(it) !in SUPPORTED }) { skipped++; continue }
            val manifest = Manifest.fromJson(JSONObject().put("tools", v.getJSONArray("tools")))
            val ep = Gate.Episode(v.getString("request"))
            val results = v.getJSONArray("tool_results")
            for (j in 0 until results.length()) ep.observeSecrets(results.getString(j))
            val verdict = Gate(manifest).enforceCall(ep, v.getString("function"), v.getJSONObject("args"))
            val expectAllow = v.getString("expect") == "allow"
            if (verdict.allowed != expectAllow) {
                val why = verdict.violations.joinToString("; ") { "${it.rule}(${it.arg}): ${it.hint}" }
                wrong += "${v.getString("name")} -> ${if (verdict.allowed) "ALLOW" else "BLOCK $why"}"
            }
        }
        println("shared vectors: ${vectors.length() - skipped} run, $skipped skipped (unsupported feature)")
        assertTrue("${wrong.size} of ${vectors.length() - skipped} vectors disagree:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }
}
