package com.agent.ultra.bench

import android.content.Context
import com.agent.ultra.bench.FeatureGrade.Result
import com.agent.ultra.bench.FeatureGrade.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
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
 * to the next one (see [Runner] for what a skip can and can't interrupt). Probes never write to the person's real memory: memory search uses its own
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
        @Volatile private var skip: CompletableDeferred<Unit>? = null

        /** "Skip this part": stop waiting for the probe running now and go on with the next one. */
        fun skipCurrent() { skip?.complete(Unit) }

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

        private sealed class Outcome {
            class Done(val results: List<Result>?) : Outcome()
            object Skipped : Outcome()
        }

        /**
         * The probe runs detached (on IO: SQLite, ONNX, whisper block), and this only WAITS for it,
         * up to its timeout or a Skip. Waiting can always be cut short; the probe itself is then
         * cancelled. A model generation stops at its next token; a blocking native call (a whisper
         * transcription, a model load) can't be interrupted and finishes in the background while
         * the next probe starts. Stop cancels the wait and the probe the same way.
         */
        private suspend fun runOne(p: Probe): List<Result> {
            val skipped = CompletableDeferred<Unit>()
            skip = skipped
            val job = probeScope.async { p.run(env) }
            try {
                val outcome = withTimeoutOrNull(p.timeoutMs) {
                    select<Outcome> {
                        job.onAwait { Outcome.Done(it) }
                        skipped.onAwait { Outcome.Skipped }
                    }
                }
                return when (outcome) {
                    null -> listOf(Result(p.title, Verdict.FAIL, "timed out after ${p.timeoutMs / 1000} s",
                        "did not finish in time; the rest of the check went on"))
                    Outcome.Skipped -> listOf(Result(p.title, Verdict.WARN, "skipped", "you skipped it, so it was not measured"))
                    is Outcome.Done -> outcome.results?.takeIf { it.isNotEmpty() }
                        ?: listOf(Result(p.title, Verdict.FAIL, "no result", "the probe returned nothing"))
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()           // Stop: propagate
                // The probe itself was cancelled from inside (e.g. its own coroutine): not the person.
                return crashed(p, e)
            } catch (e: Throwable) {
                return crashed(p, e)                                // the probe threw (await rethrows it)
            } finally {
                skip = null
                if (!job.isCompleted) job.cancel(CancellationException("skipped, timed out or stopped"))
            }
        }

        private fun crashed(p: Probe, e: Throwable) = listOf(Result(p.title, Verdict.FAIL,
            "crashed: ${e.javaClass.simpleName}", "the test itself hit an error: ${e.message?.take(160)}",
            e.stackTrace.take(8).map { "  at $it" }))

        /** Detached from the caller: a probe stuck in native code must not hold the run up. */
        private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
