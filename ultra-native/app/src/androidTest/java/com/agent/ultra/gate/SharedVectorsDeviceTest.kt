package com.agent.ultra.gate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared gate vectors, run ON THE PHONE. Android ships its own org.json (the unit
 * tests use the desktop artifact), and ART is not the desktop JVM, so agreement here is
 * the device-level proof that the port behaves as the Python gate does. Same file as
 * SharedVectorsTest (src/test/resources, wired in as androidTest assets).
 */
@RunWith(AndroidJUnit4::class)
class SharedVectorsDeviceTest {
    private val supported = setOf<String>()

    @Test
    fun everyVectorAgreesOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val text = ctx.assets.open("gate_vectors.json").bufferedReader().use { it.readText() }
        val vectors = JSONObject(text).getJSONArray("vectors")
        val wrong = mutableListOf<String>()
        var skipped = 0
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val needs = v.optJSONArray("needs")
            if (needs != null && (0 until needs.length()).any { needs.getString(it) !in supported }) { skipped++; continue }
            val manifest = Manifest.fromJson(JSONObject().put("tools", v.getJSONArray("tools")))
            val ep = Gate.Episode(v.getString("request"))
            val results = v.getJSONArray("tool_results")
            for (j in 0 until results.length()) ep.observeSecrets(results.getString(j))
            val verdict = Gate(manifest).enforceCall(ep, v.getString("function"), v.getJSONObject("args"))
            if (verdict.allowed != (v.getString("expect") == "allow")) {
                wrong += "${v.getString("name")} -> ${if (verdict.allowed) "ALLOW" else "BLOCK " + verdict.rule}"
            }
        }
        android.util.Log.i("UltraGateVectors", "device vectors: ${vectors.length() - skipped} run, $skipped skipped, ${wrong.size} disagree")
        assertTrue("${wrong.size} of ${vectors.length() - skipped} vectors disagree on device:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }
}
