package com.agent.ultra.bench

import android.content.Context
import com.agent.ultra.bench.FeatureGrade.Result
import com.agent.ultra.bench.FeatureGrade.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Feature check", DEBUG BUILDS ONLY (src/debug; release APKs contain none of it): every feature
 * of the app, tested on the phone and scored, one line each:
 *
 *   PASS    Memory search: top-1 4/5, top-3 5/5 …  — finds what was said by meaning, words and day
 *
 * Run from the "Jarvis Tests" screen ([TestKitActivity]). Scoring and the fixed test inputs are
 * pure and JVM-tested ([FeatureGrade], [FeatureCases]); the probes themselves are in
 * [FeatureProbes].
 *
 * Every probe has a timeout, a crash in one never stops the rest, and "Skip this part" moves on
 * to the next one. Probes never write to the person's real memory: memory search uses its own
 * sandbox database file, deleted afterwards; the librarian probes parse their notes but never
 * store them.
 *
 * TO ADD A PROBE: write `suspend fun myThing(env: ProbeEnv): List<Result>` in [FeatureProbes]
 * and add ONE line to [PROBES]: `Probe("My thing", 60_000, FeatureProbes::myThing)`.
 */
object FeatureCheck {

    /** One entry of the registry. [title] names it when it times out, crashes or is skipped. */
    class Probe(val title: String, val timeoutMs: Long, val run: suspend (ProbeEnv) -> List<Result>)

    /** In run order: quick pure checks first, then the models, the live phone action last. */
    val PROBES: List<Probe> = listOf(
        Probe("Native libraries & CPU", 30_000, FeatureProbes::nativeCpu),
        Probe("History policy", 10_000, FeatureProbes::historyPolicy),
        Probe("Safety gates", 10_000, FeatureProbes::safetyGates),
        Probe("Offline phone control (rules)", 10_000, FeatureProbes::offlineNavRules),
        Probe("Ambient listening", 20_000, FeatureProbes::ambient),
        Probe("Memory search", 3 * 60_000, FeatureProbes::memorySearch),
        Probe("Voice in and out", 4 * 60_000, FeatureProbes::voice),
        Probe("Transcript cues", 4 * 60_000, FeatureProbes::transcriptCues),
        Probe("Memory model (librarian)", 6 * 60_000, FeatureProbes::librarian),
        Probe("Pattern notes", 6 * 60_000, FeatureProbes::patternNotes),
        Probe("Thinker model", 6 * 60_000, FeatureProbes::thinkerSpeed),
        Probe("Thinker quality", 15 * 60_000, FeatureProbes::thinkerQuality),
        Probe("Thinker no-copying (Q8/Q11)", 10 * 60_000, FeatureProbes::thinkerRegression),
        Probe("Offline phone control (live)", 6 * 60_000, FeatureProbes::offlineNavLive),
    )

    /** Runs [probes] in order; [onResults] gets each probe's results as soon as it ends. */
    class Runner(private val env: ProbeEnv, private val probes: List<Probe> = PROBES) {
        @Volatile private var current: Deferred<List<Result>?>? = null

        /** "Skip this part": cancel the probe running now; the run goes on with the next one. */
        fun skipCurrent() { current?.cancel(CancellationException("skipped")) }

        suspend fun run(onStart: suspend (Probe, Int) -> Unit, onResults: suspend (Probe, List<Result>) -> Unit): List<Result> {
            val all = ArrayList<Result>()
            for ((i, p) in probes.withIndex()) {
                currentCoroutineContext().ensureActive()           // Stop: the whole run ends here
                onStart(p, i)
                val t0 = System.currentTimeMillis()
                val out = runOne(p)
                val secs = (System.currentTimeMillis() - t0) / 1000
                val stamped = out.map { it.copy(detail = it.detail + "(probe took $secs s)") }
                all += stamped
                onResults(p, stamped)
            }
            return all
        }

        private suspend fun runOne(p: Probe): List<Result> = supervisorScope {
            // IO: probes make blocking calls (SQLite, ONNX, whisper); the UI callbacks switch to Main.
            val job = async(Dispatchers.IO) { withTimeoutOrNull(p.timeoutMs) { p.run(env) } }
            current = job
            try {
                job.await()?.takeIf { it.isNotEmpty() }
                    ?: listOf(Result(p.title, Verdict.FAIL, "timed out after ${p.timeoutMs / 1000} s",
                        "did not finish in time; the rest of the check went on"))
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()           // Stop (not Skip): propagate
                listOf(Result(p.title, Verdict.WARN, "skipped", "you skipped it, so it was not measured"))
            } catch (e: Throwable) {
                listOf(Result(p.title, Verdict.FAIL, "crashed: ${e.javaClass.simpleName}",
                    "the test itself hit an error: ${e.message?.take(160)}",
                    e.stackTrace.take(8).map { "  at $it" }))
            } finally {
                current = null
            }
        }
    }
}

/**
 * What a probe may use. [ctx] is the application context. [say]: a live line on screen (not
 * saved; put what matters in the result's detail). [status]: the one-line "now doing …".
 * [confirm]: a Yes/No dialog, false when the person says No. [askBrain] runs one request
 * through the real chat brain in the current chat and returns its answer; [newChat] starts a
 * fresh chat first. [online]: any network (the real offline path needs airplane mode).
 * [logcat]: this app's own log lines for the given tags since an epoch-ms time.
 */
class ProbeEnv(
    val ctx: Context,
    val say: suspend (String) -> Unit,
    val status: (String) -> Unit,
    val confirm: suspend (String) -> Boolean,
    val askBrain: suspend (String) -> String,
    val newChat: suspend () -> Unit,
    val online: () -> Boolean,
    val logcat: (Long, List<String>) -> List<String>,
)
