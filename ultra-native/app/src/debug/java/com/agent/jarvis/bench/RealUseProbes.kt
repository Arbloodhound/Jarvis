package com.agent.jarvis.bench

import com.agent.jarvis.agent.Brain
import com.agent.jarvis.bench.FeatureGrade.Result
import com.agent.jarvis.bench.FeatureGrade.Verdict
import com.agent.jarvis.listen.AmbientConfig
import com.agent.jarvis.listen.AmbientListenService
import com.agent.jarvis.listen.AmbientMemory
import com.agent.jarvis.listen.LocalVoice
import com.agent.jarvis.local.LocalModelEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Real-use check (owner, 2026-10-07: "check all the features by actually using them, not
 * samples … real noise from the room … automated, I'm tired"). Hands-free, about five minutes:
 * nothing here feeds the app a recording. It listens to the real room, speaks with the real chosen
 * voice, wakes the real listening service with "Hey Jarvis" said out loud through the phone's own
 * speaker, runs the real brain with a spoken request, stops a real run mid-answer, and times a real
 * model download. Read from the running services' own markers and logs.
 */
object RealUseProbes {
    private fun secs(ms: Long) = FeatureGrade.f1(ms / 1000.0) + " s"
    private fun oneLine(s: String, max: Int = 160) = s.replace(Regex("\\s+"), " ").trim().take(max)

    private suspend fun listening(env: ProbeEnv): Boolean {
        if (!AmbientConfig(env.ctx).enabled) return false
        AmbientListenService.resumeIfOff(env.ctx)
        return withTimeoutOrNull(10_000) { while (AmbientListenService.instance?.capturing != true) delay(250); true } ?: false
    }

    /** 60 s of the real room: what was heard and stored, how fast whisper kept up, who was labelled. */
    suspend fun room(env: ProbeEnv): List<Result> {
        val f = "Real room listening (60 s)"
        if (!listening(env)) return listOf(Result(f, Verdict.FAIL, "not listening", "turn on Settings → VOICE → Ambient listening"))
        val t0 = System.currentTimeMillis()
        env.status("Listening to the room for 60 s — talk near the phone if you like")
        delay(60_000)
        val lines = AmbientMemory.get(env.ctx).newestTagged(200).filter { it.ts >= t0 }
        val whisper = env.logcat(t0, listOf("JarvisAmbient")).mapNotNull { l ->
            Regex("audioMs=(\\d+) ms=(\\d+)").find(l)?.let { it.groupValues[1].toLong() to it.groupValues[2].toLong() }
        }
        val rtf = whisper.filter { it.first > 0 }.map { it.second.toDouble() / it.first }.sorted()
        val medRtf = rtf.getOrNull(rtf.size / 2)
        val labelled = lines.count { Regex("^\\[(?!Unknown\\])[^\\]]+\\]").containsMatchIn(it.text) }
        val model = AmbientListenService.asrModelName.ifBlank { "?" }
        val v = when {
            whisper.isEmpty() && lines.isEmpty() -> Verdict.WARN
            medRtf != null && medRtf > 1.0 -> Verdict.FAIL
            else -> Verdict.PASS
        }
        return listOf(Result(f, v,
            "${lines.size} line(s) stored, ${whisper.size} utterance(s) transcribed, speed ${medRtf?.let { FeatureGrade.f1(it) + "× real time" } ?: "-"}, " +
                "speaker label on $labelled/${lines.size}, model $model",
            when (v) {
                Verdict.WARN -> "nothing was said near the phone in the minute (or it heard nothing): talk during this part"
                Verdict.FAIL -> "speech recognition is slower than speech here: it will fall behind (Accurate speech recognition off helps)"
                else -> "hears the real room and keeps up"
            },
            listOf("speech model: $model") + lines.take(12).map { "heard: ${oneLine(it.text)}" }))
    }

    /** The chosen voice, for real: synthesis speed against the length of the speech, then played. */
    suspend fun voiceOut(env: ProbeEnv): List<Result> {
        val f = "Jarvis's voice (the one chosen)"
        if (!LocalVoice.available) return listOf(Result(f, Verdict.MISSING, "voice library missing", "this build can't speak with its own voice"))
        val m = LocalVoice.speakingModel(env.ctx)
            ?: return listOf(Result(f, Verdict.WARN, "phone's voice", "no own voice downloaded (or the phone's voice is chosen): Settings → JARVIS'S VOICE"))
        val text = "Good evening. Everything is running, and I'm ready when you are."
        val t0 = System.currentTimeMillis()
        val a = LocalVoice.generate(env.ctx, text)
        val synthMs = System.currentTimeMillis() - t0
        if (a == null) return listOf(Result(f, Verdict.FAIL, "${m.label}: no audio", "the voice failed to speak"))
        val audioMs = a.samples.size * 1000L / a.sampleRate
        val rtf = synthMs.toDouble() / audioMs.coerceAtLeast(1)
        env.status("Playing the voice")
        val sp = com.agent.jarvis.ui.Speaker(env.ctx)
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { sp.speak(text) { done.complete(Unit) } }
        withTimeoutOrNull(20_000) { done.await() }
        sp.shutdown()
        val v = when { rtf < 0.5 -> Verdict.PASS; rtf < 1.0 -> Verdict.WARN; else -> Verdict.FAIL }
        return listOf(Result(f, v, "${m.label}: ${secs(synthMs)} to make ${secs(audioMs)} of speech (${FeatureGrade.f1(rtf)}×)",
            when (v) {
                Verdict.PASS -> "fast enough to talk sentence by sentence without gaps"
                Verdict.WARN -> "keeps up, but long answers may have short gaps between sentences"
                else -> "slower than speaking: pick a lighter voice (Ryan, Cori) in Settings → JARVIS'S VOICE"
            }))
    }

    /**
     * "Hey Jarvis, what time is it?" said out loud by the phone's own speaker, heard by the real mic
     * and the running listener (nothing is fed to it): woke? command? first spoken word, total.
     */
    suspend fun loopback(env: ProbeEnv): List<Result> {
        val f = "Hey Jarvis, out loud (end to end)"
        if (!listening(env)) return listOf(Result(f, Verdict.FAIL, "not listening", "turn on Settings → VOICE → Ambient listening"))
        val t0 = System.currentTimeMillis()
        env.status("Saying \"Hey Jarvis, what time is it?\" through the speaker")
        val sp = com.agent.jarvis.ui.Speaker(env.ctx, heardByListener = true)   // the listener must hear this one
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { sp.speak("Hey Jarvis. What time is it?") }
        val woke = withTimeoutOrNull(20_000) { while (AmbientListenService.lastWakeAt < t0) delay(150); true } ?: false
        sp.shutdown()
        val cmd = woke && (withTimeoutOrNull(30_000) { while (AmbientListenService.lastCommandAt < t0) delay(200); true } ?: false)
        val spoke = cmd && (withTimeoutOrNull(90_000) { while (AmbientListenService.lastAnswerStartAt < AmbientListenService.lastCommandAt) delay(200); true } ?: false)
        val done = cmd && (withTimeoutOrNull(120_000) {
            while (AmbientListenService.lastCommandDoneAt < AmbientListenService.lastCommandAt) delay(300); true } ?: false)
        val firstWord = if (spoke) AmbientListenService.lastAnswerStartAt - AmbientListenService.lastCommandAt else -1L
        val v = when {
            !woke -> Verdict.WARN
            spoke && firstWord <= 12_000 -> Verdict.PASS
            spoke -> Verdict.WARN
            else -> Verdict.FAIL
        }
        return listOf(Result(f, v,
            "woke ${if (woke) "yes (${AmbientListenService.lastWakeBy}, ${secs(AmbientListenService.lastWakeAt - t0)})" else "no"}, " +
                "command ${if (cmd) "\"${oneLine(com.agent.jarvis.agent.IntentRouter.requestPart(AmbientListenService.lastCommand), 40)}\"" else "none"}, " +
                "first spoken word ${if (spoke) "after ${secs(firstWord)}" else "never"}${if (done) ", finished after ${secs(AmbientListenService.lastCommandDoneAt - AmbientListenService.lastCommandAt)}" else ""}",
            when {
                !woke -> "the phone's own voice through its speaker didn't wake it (the wake model is trained on real voices): the Feature check's Live wake has you say it yourself"
                !cmd -> "it woke but no command reached the brain"
                !spoke -> "the command reached the brain but nothing was said back"
                v == Verdict.PASS -> "woke, understood and started answering out loud within seconds"
                else -> "works, but the first spoken word took over 12 s"
            }))
    }

    /** A spoken request through the real brain: time to the first sentence, total, and correct? */
    suspend fun spokenAnswer(env: ProbeEnv): List<Result> {
        val f = "Spoken answer speed (real brain)"
        val engine = LocalModelEngine.shared(env.ctx)
        if (!engine.modelPresent) return listOf(Result(f, Verdict.MISSING, "no brain downloaded", "Settings → ON-DEVICE MODEL"))
        env.status("Loading the brain")
        if (!engine.ensureLoaded()) return listOf(Result(f, Verdict.FAIL, "brain didn't load", "the on-device model failed to load"))
        val parts = ArrayList<Pair<Long, String>>()
        val brain = Brain(env.ctx, engine).also { b ->
            b.recordTurns = false
            b.onSpeak = { t -> synchronized(parts) { parts += System.currentTimeMillis() to t } }
        }
        env.status("Asking the brain out loud: capital of France")
        val t0 = System.currentTimeMillis()
        withTimeoutOrNull(120_000) { brain.run("What is the capital of France? Answer in one short sentence.", spoken = true) }
        val total = System.currentTimeMillis() - t0
        val said = synchronized(parts) { parts.toList() }
        val first = said.firstOrNull()?.first?.minus(t0)
        val text = said.joinToString(" ") { it.second }
        val right = text.contains("Paris", ignoreCase = true)
        val v = when { first == null || !right -> Verdict.FAIL; first <= 10_000 -> Verdict.PASS; else -> Verdict.WARN }
        return listOf(Result(f, v, "first sentence after ${first?.let { secs(it) } ?: "never"}, done in ${secs(total)}, right: ${if (right) "yes" else "no"}",
            when {
                first == null -> "nothing was spoken"
                !right -> "spoke, but the answer was wrong"
                v == Verdict.PASS -> "answers out loud within seconds"
                else -> "right, but slow to start talking (Think time: Instant in Settings → HOW JARVIS TALKS helps)"
            },
            listOf("said: ${oneLine(text, 200)}")))
    }

    /**
     * Offline only, for real: the switch on, the real brain asked something that wants the web
     * ("what's the latest news today"), then the audit log read back. PASS when no line written
     * since the start routed to the cloud or the web, and at least one routed on-device. The
     * owner's setting is put back afterwards, whatever happens.
     */
    suspend fun offlineOnly(env: ProbeEnv): List<Result> {
        val f = "Offline only, for real"
        val engine = LocalModelEngine.shared(env.ctx)
        if (!engine.modelPresent) return listOf(Result(f, Verdict.MISSING, "no brain downloaded", "needs the on-device model: Settings → ON-DEVICE MODEL"))
        if (!com.agent.jarvis.gate.GateAuditLog.isInitialized()) return listOf(Result(f, Verdict.FAIL, "audit log not started", "nothing to read the routes from"))
        val was = com.agent.jarvis.ui.JarvisPrefs.offlineOnly(env.ctx)
        try {
            com.agent.jarvis.ui.JarvisPrefs.setOfflineOnly(env.ctx, true)
            env.status("Loading the brain")
            if (!engine.ensureLoaded()) return listOf(Result(f, Verdict.FAIL, "brain didn't load", "the on-device model failed to load"))
            val brain = Brain(env.ctx, engine).also { it.recordTurns = false }
            env.status("Offline only on: asking the brain for today's news")
            val t0 = System.currentTimeMillis()
            val finished = withTimeoutOrNull(5 * 60_000) { brain.run("what's the latest news today"); true } ?: false
            if (!finished) brain.stop()
            delay(1_500)   // the audit log is written on its own thread
            val routes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val out = java.io.StringWriter()
                com.agent.jarvis.gate.GateAuditLog.writeEntries(java.io.File(env.ctx.filesDir, "gate_audit.jsonl"), out)
                out.toString().lineSequence().mapNotNull { l -> try { org.json.JSONObject(l) } catch (_: Exception) { null } }
                    .filter { it.optString("kind") == "route" && it.optLong("ts") >= t0 }
                    .map { it.optString("route") + "/" + it.optString("reason") }.toList()
            }
            val net = routes.filter { it.substringBefore('/') in setOf(
                com.agent.jarvis.gate.GateAuditLog.ROUTE_CLOUD, com.agent.jarvis.gate.GateAuditLog.ROUTE_WEB) }
            val local = routes.filter { it.startsWith("local_") }
            val v = if (net.isEmpty() && local.isNotEmpty()) Verdict.PASS else Verdict.FAIL
            return listOf(Result(f, v, "routes: ${routes.joinToString(", ").ifBlank { "none" }}${if (finished) "" else " (timed out)"}",
                when {
                    net.isNotEmpty() -> "used the cloud or the web with offline only on"
                    local.isEmpty() -> "no on-device route was recorded"
                    else -> "stayed on the phone: no cloud, no web"
                }))
        } finally {
            com.agent.jarvis.ui.JarvisPrefs.setOfflineOnly(env.ctx, was)
        }
    }

    /** A real run stopped mid-answer, as "stop" does: how long until the brain lets go. */
    suspend fun stop(env: ProbeEnv): List<Result> {
        val f = "\"Stop\" mid-answer"
        val engine = LocalModelEngine.shared(env.ctx)
        if (!engine.modelPresent || !engine.ensureLoaded()) return listOf(Result(f, Verdict.MISSING, "no brain", "needs the on-device model"))
        val brain = Brain(env.ctx, engine).also { it.recordTurns = false }
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            brain.run("Explain in detail how a car engine works, step by step.")
        }
        val started = withTimeoutOrNull(60_000) { engine.busy.first { it }; true } ?: false
        if (!started) { job.cancel(); return listOf(Result(f, Verdict.FAIL, "the brain never started", "couldn't test stopping")) }
        delay(3_000)
        val t0 = System.currentTimeMillis()
        Brain.stopAll()
        val freed = withTimeoutOrNull(15_000) { engine.busy.first { !it }; true } ?: false
        val ms = System.currentTimeMillis() - t0
        job.cancel()
        val v = when { !freed -> Verdict.FAIL; ms <= 2_000 -> Verdict.PASS; else -> Verdict.WARN }
        return listOf(Result(f, v, if (freed) "stopped in ${secs(ms)}" else "still running after 15 s",
            if (v == Verdict.PASS) "stops almost at once" else "stopping is slow"))
    }

    /** One connection against four for 25 MB of the brain's own file: is the parallel download faster? */
    suspend fun download(env: ProbeEnv): List<Result> {
        val f = "Model download speed"
        if (!env.online()) return listOf(Result(f, Verdict.WARN, "offline", "turn Wi-Fi on for this part"))
        val url = LocalModelEngine.PRESETS.firstOrNull { it.fileName == LocalModelEngine.shared(env.ctx).modelFileName }?.url
            ?: LocalModelEngine.FAST_DEFAULT.url
        val client = okhttp3.OkHttpClient.Builder().followRedirects(true)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        fun fetch(from: Long, to: Long): Long = client.newCall(okhttp3.Request.Builder().url(url)
            .header("Range", "bytes=$from-$to").build()).execute().use { r ->
            if (r.code != 206) return@use -1L
            var n = 0L; val buf = ByteArray(256 * 1024)
            r.body!!.byteStream().use { s -> while (true) { val k = s.read(buf); if (k < 0) break; n += k } }
            n
        }
        val size = 24L * 1_048_576
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            env.status("Download speed: one connection")
            var t = System.currentTimeMillis()
            val one = fetch(0, size - 1)
            val oneMs = System.currentTimeMillis() - t
            env.status("Download speed: four connections")
            t = System.currentTimeMillis()
            val q = size / 4
            val four = kotlinx.coroutines.coroutineScope {
                (0 until 4).map { i -> async { fetch(size + i * q, size + (i + 1) * q - 1) } }.awaitAll()
            }
            val fourMs = System.currentTimeMillis() - t
            if (one < 0 || four.any { it < 0 }) return@withContext listOf(Result(f, Verdict.WARN, "server didn't serve byte ranges",
                "downloads use one connection"))
            fun mbps(b: Long, ms: Long) = b / 1_048_576.0 / (ms / 1000.0)
            val s1 = mbps(one, oneMs); val s4 = mbps(four.sum(), fourMs)
            listOf(Result(f, Verdict.PASS, "1 connection ${FeatureGrade.f1(s1)} MB/s, 4 connections ${FeatureGrade.f1(s4)} MB/s",
                if (s4 > s1 * 1.2) "four connections are ${FeatureGrade.f1(s4 / s1)}× faster here" else "your connection is the limit, not the server"))
        }
    }
}
