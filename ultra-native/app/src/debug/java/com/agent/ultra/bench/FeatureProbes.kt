package com.agent.ultra.bench

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.agent.ultra.agent.LocalPrompt
import com.agent.ultra.agent.ThermalGuard
import com.agent.ultra.agent.ThermalPolicy
import com.agent.ultra.agent.ThinkBudget
import com.agent.ultra.bench.FeatureGrade.Result
import com.agent.ultra.bench.FeatureGrade.Verdict
import com.agent.ultra.listen.AmbientConfig
import com.agent.ultra.listen.AmbientListenService
import com.agent.ultra.listen.AmbientMemory
import com.agent.ultra.listen.CueAudio
import com.agent.ultra.listen.CueTags
import com.agent.ultra.listen.CueText
import com.agent.ultra.listen.LibrarianPrompt
import com.agent.ultra.listen.LocalVoice
import com.agent.ultra.listen.LocalWhisper
import com.agent.ultra.listen.MemoryRecall
import com.agent.ultra.listen.MemoryVectors
import com.agent.ultra.listen.NoteParser
import com.agent.ultra.listen.ObservationText
import com.agent.ultra.listen.ReadingPrompt
import com.agent.ultra.listen.Segmenter
import com.agent.ultra.listen.SentenceEmbedder
import com.agent.ultra.listen.TranscriptCueWorker
import com.agent.ultra.listen.TurnGaps
import com.agent.ultra.listen.WakeFilter
import com.agent.ultra.listen.WakeGate
import com.agent.ultra.listen.WhisperNative
import com.agent.ultra.local.CpuTopology
import com.agent.ultra.local.LlmNative
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.local.PageFaults
import com.agent.ultra.ui.UltraPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.ZoneId

/**
 * The probes of the debug-only Feature check ([FeatureCheck.PROBES]). Each returns one or more
 * [Result]s; every number in them is measured here, on this phone, in this run. DEBUG ONLY.
 */
object FeatureProbes {

    private fun ms(t0: Long) = System.currentTimeMillis() - t0
    private fun secs(ms: Long) = FeatureGrade.f1(ms / 1000.0) + " s"
    private fun oneLine(s: String, max: Int = 200) = s.replace(Regex("\\s+"), " ").trim().take(max)

    // ── 1. Native libraries, CPU, thermal ──────────────────────────────────────────────────

    suspend fun nativeCpu(env: ProbeEnv): List<Result> {
        val llama = if (LlmNative.available) LlmNative.variant else null
        val whisper = if (WhisperNative.available) WhisperNative.variant else null
        val topo = CpuTopology.current
        val numbers = "llama=${llama ?: "NOT LOADED"} whisper=${whisper ?: "NOT LOADED"} " +
            "LLM threads=${topo.llmThreads} on cpus ${topo.llmCpus.ifEmpty { listOf("any") }}"
        val best = { v: String? -> v != null && v.endsWith("_i8mm") }
        val verdict = when {
            llama == null -> Verdict.FAIL
            whisper == null -> Verdict.FAIL
            !best(llama) || !best(whisper) -> Verdict.WARN
            else -> Verdict.PASS
        }
        val meaning = when (verdict) {
            Verdict.PASS -> "the fastest CPU builds of the model and speech engines loaded"
            Verdict.WARN -> "engines load, but not the fastest CPU build (slower answers)"
            else -> "an engine library did not load: that part of Jarvis cannot run"
        }
        val cpu = Result("Native libraries & CPU", verdict, numbers, meaning, listOf(
            "llama variant=$llama available=${LlmNative.available}",
            "whisper variant=$whisper available=${WhisperNative.available}",
            "fastest first=${topo.fastestFirst} big=${topo.big} llmCpus=${topo.llmCpus} whisperCpus=${topo.whisperCpus}",
        ))

        val pm = env.ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val status = if (Build.VERSION.SDK_INT >= 29) runCatching { pm?.currentThermalStatus }.getOrNull() else null
        val level = ThermalGuard.level
        val head = ThermalGuard.lastHeadroom
        val tv = when (level) {
            ThermalPolicy.Level.NORMAL -> Verdict.PASS
            ThermalPolicy.Level.WARM, ThermalPolicy.Level.HOT -> Verdict.WARN
            ThermalPolicy.Level.CRITICAL -> Verdict.FAIL
        }
        val threads = ThermalPolicy.threadsFor(level, topo.llmThreads)
        val thermal = Result("Thermal state", tv,
            "level=$level headroom=${if (head.isNaN()) "n/a" else FeatureGrade.f2(head.toDouble())} os status=${status ?: "n/a"} → $threads LLM threads",
            when (tv) {
                Verdict.PASS -> "phone is cool: answers run at full speed"
                Verdict.WARN -> "phone is warm: Jarvis uses fewer threads and shorter thinking (slower answers)"
                else -> "phone is too hot: only device commands run"
            },
            listOf("headroom thresholds: warm ${ThermalPolicy.WARM_AT}, hot ${ThermalPolicy.HOT_AT}, " +
                "critical ${ThermalPolicy.CRIT_AT} (1.0 = the OS's severe throttling)"))
        return listOf(cpu, thermal)
    }

    // ── 5, 9, 8a. Pure decisions (same cases as the JVM tests) ─────────────────────────────

    private fun scored(feature: String, cases: List<FeatureCases.Case>, good: String, bad: String, extra: List<String> = emptyList()): Result {
        val ok = cases.count { it.ok }
        val v = if (ok == cases.size) Verdict.PASS else Verdict.FAIL
        return Result(feature, v, "$ok/${cases.size} cases right", if (v == Verdict.PASS) good else bad,
            extra + cases.map { it.line() })
    }

    suspend fun historyPolicy(env: ProbeEnv): List<Result> = listOf(scored(
        "History policy (Q8/Q11 fix)", FeatureCases.historyCases(),
        "only a request that refers back to the chat gets the earlier answers",
        "a request gets earlier answers it should not (or misses ones it needs)",
    ))

    suspend fun safetyGates(env: ProbeEnv): List<Result> {
        val skip = UltraPrefs.skipConfirmWhenAsked(env.ctx)
        return listOf(scored(
            "Safety gates", FeatureCases.gateCases(skip),
            "wrong amounts are refused, money/deletion ask first, asked-for actions go ahead",
            "a payment, deletion or message would be handled the wrong way",
            listOf("setting \"Don't ask when I asked for it\" = $skip"),
        ))
    }

    suspend fun offlineNavRules(env: ProbeEnv): List<Result> = listOf(scored(
        "Offline phone control (rules)", FeatureCases.navCases(),
        "the offline brain's one-line actions are read strictly and loops are caught",
        "a model reply or loop would be handled wrongly",
    ))

    // ── 10. Ambient listening ──────────────────────────────────────────────────────────────

    private fun micAllowed(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun wakeModelsPresent(ctx: Context) =
        listOf(WakeGate.MEL, WakeGate.EMB, WakeGate.cfgName(ctx)).all { File(ctx.filesDir, it).exists() }

    suspend fun ambient(env: ProbeEnv): List<Result> {
        val ctx = env.ctx
        val cfg = AmbientConfig(ctx)
        val on = cfg.enabled
        val running = AmbientListenService.instance != null
        val mic = micAllowed(ctx)
        val wake = wakeModelsPresent(ctx)
        val asr = LocalWhisper.modelPresent(ctx)
        // Read-only look at the real store: the newest line's time, nothing else.
        val last = runCatching { AmbientMemory.get(ctx).newest(1).firstOrNull()?.ts }.getOrNull()
        val ageMin = last?.let { (System.currentTimeMillis() - it) / 60_000 }
        val numbers = "on=${yn(on)} running=${yn(running)} mic=${if (mic) "allowed" else "DENIED"} " +
            "wake models=${yn(wake)} speech model=${yn(asr)} last heard=${ageMin?.let { ago(it) } ?: "never"}"
        val (v, meaning) = when {
            !on -> Verdict.MISSING to "ambient listening is switched off in Settings"
            !mic -> Verdict.FAIL to "it is on, but the microphone permission is denied"
            !running -> Verdict.FAIL to "it is on, but the listening service is not running"
            !asr || !wake -> Verdict.WARN to "listening, but a model is still missing (downloads on Wi-Fi)"
            ageMin == null || ageMin > 24 * 60 -> Verdict.WARN to "listening, but nothing was stored in the last day"
            else -> Verdict.PASS to "listening, and storing what it hears"
        }
        // Since the service started (this process): speech, whisper runs, drops. "last heard" above is
        // the newest stored line; these say whether speech was heard but held, dropped or filtered out.
        fun since(ts: Long) = if (ts <= 0L) "never" else ago((System.currentTimeMillis() - ts) / 60_000)
        val pipeline = "since start: last speech ${since(AmbientListenService.lastSpeechAt)}, " +
            "last transcribed ${since(AmbientListenService.lastTranscribedAt)}, " +
            "dropped untranscribed ${AmbientListenService.dropped.get()}"
        return listOf(Result("Ambient listening", v, numbers, meaning,
            listOf("wake name=\"${cfg.wakeName}\" retention=${cfg.retentionDays} days", pipeline)))
    }

    private fun ago(min: Long) = when {
        min < 60 -> "$min min ago"
        min < 48 * 60 -> "${min / 60} h ago"
        else -> "${min / (24 * 60)} days ago"
    }

    // ── 11. Transcript cues (pauses, word timing, speakers) ────────────────────────────────

    const val CUE_FIRST = "I was thinking we could go to the market on Saturday."
    const val CUE_SECOND = "Or maybe we should just stay home and rest instead."
    const val CUE_PAUSE_MS = 1_500

    suspend fun transcriptCues(env: ProbeEnv): List<Result> {
        val ctx = env.ctx
        val ttsOk = LocalVoice.available && LocalVoice.voicePresent(ctx)
        val sttOk = WhisperNative.available && LocalWhisper.modelPresent(ctx)
        val speaker = speakerTags(env)
        if (!ttsOk || !sttOk) {
            val why = "needs Jarvis's voice and the speech model (${if (!ttsOk) "voice" else "speech model"} missing)"
            return listOf(
                Result("Transcript cues: pauses", Verdict.MISSING, why, "can't make or hear the test audio"),
                Result("Transcript cues: word timing", Verdict.MISSING, why, "can't make or hear the test audio"),
            ) + speaker
        }
        // Two sentences in Jarvis's voice with 1.5 s of silence between them, as ambient would hear it.
        env.status("Transcript cues: making test speech with a 1.5 s pause")
        val (a, _) = speak16k(ctx, CUE_FIRST, 200, 0) ?: return listOf(
            Result("Transcript cues: pauses", Verdict.FAIL, "voice produced no audio", "couldn't make the test audio")) + speaker
        val (b, _) = speak16k(ctx, CUE_SECOND, CUE_PAUSE_MS, 400) ?: return listOf(
            Result("Transcript cues: pauses", Verdict.FAIL, "voice produced no audio", "couldn't make the test audio")) + speaker
        val pcm = a + b
        // Whisper with word timing on, its own context, the ambient prompt; freed afterwards.
        env.status("Transcript cues: transcribing with word timing")
        val prompt = WhisperNative.verbatimPrompt(AmbientConfig(ctx).wakeName)
        var sttMs = 0L
        val decoded = ambientWhisper(ctx) { h ->
            val t0 = System.currentTimeMillis()
            val whole = ambientTranscribe(h, pcm, prompt, timing = true)
            sttMs = ms(t0)
            env.status("Transcript cues: cutting and tagging the clip as ambient listening would")
            whole to cutAndTag(h, pcm, prompt)
        } ?: return listOf(
            Result("Transcript cues: word timing", Verdict.FAIL, "speech model did not load", "whisper could not start")) + speaker
        val (r, live) = decoded
        val words = r?.words.orEmpty()
        val times = words.map { it.t0Ms to it.t1Ms }
        val increasing = FeatureGrade.timesIncrease(times)
        val refWords = FeatureGrade.words("$CUE_FIRST $CUE_SECOND").size
        val timingV = when {
            r == null -> Verdict.FAIL
            words.isEmpty() -> Verdict.FAIL
            !increasing -> Verdict.FAIL
            words.size < refWords / 2 -> Verdict.WARN
            else -> Verdict.PASS
        }
        val timing = Result("Transcript cues: word timing", timingV,
            "${words.size} timed words of $refWords, times ${if (increasing) "increase" else "DO NOT increase"}, " +
                "${r?.vad?.size ?: 0} speech stretches, whisper ${secs(sttMs)}",
            when (timingV) {
                Verdict.PASS -> "whisper says when each word was spoken, so pauses can be placed"
                Verdict.WARN -> "word times come back, but for few of the words"
                else -> "no usable word times: pauses can't be placed in the line"
            },
            listOf("heard: ${r?.text ?: "(nothing)"}",
                "words: " + words.take(24).joinToString(" ") { "${it.text}@${it.t0Ms}" },
                "vad: " + r?.vad.orEmpty().joinToString(" ") { "${it.startMs}-${it.endMs}" }))

        // The live path, step for step ([cutAndTag]): ambient's Segmenter cuts the clip (its own
        // noise floor and gate), each utterance gets whisper, the pause before it and the pauses
        // inside it, exactly as the service and the cue worker would tag them.
        val tagged = live.flatMap { FeatureGrade.pausesIn(it.line) }
        val longest = tagged.maxOrNull()
        // The voice adds its own short silence at the end of a sentence, so a bit over 1.5 s is right.
        val pauseV = when {
            longest == null -> Verdict.FAIL
            longest in 1.3..2.3 && tagged.size == 1 -> Verdict.PASS
            longest in 1.0..3.0 -> Verdict.WARN
            else -> Verdict.FAIL
        }
        val pause = Result("Transcript cues: pauses", pauseV,
            "inserted ${FeatureGrade.f1(CUE_PAUSE_MS / 1000.0)} s → tagged ${if (tagged.isEmpty()) "none" else tagged.joinToString { "${it}s" }}",
            when (pauseV) {
                Verdict.PASS -> "a pause in speech is measured and tagged at the right length"
                Verdict.WARN -> "the pause is tagged, but its length or count is off"
                else -> "the pause was not tagged"
            },
            listOf("cut by ambient's Segmenter into ${live.size} utterance(s) (a silence over " +
                "${AmbientConfig.HANGOVER_MS} ms ends one; the pause before the next is then measured between them)") +
                live.mapIndexed { i, u -> "utterance ${i + 1}: ${u.describe()}" })
        return listOf(pause, timing) + speaker
    }

    /** One utterance of the test clip as ambient listening would cut, hear and tag it. */
    private class LiveUtterance(
        val startMs: Long, val endMs: Long, val floorDb: Float, val gapBeforeMs: Long?,
        val inside: List<com.agent.ultra.listen.Span>, val line: String, val heard: String?,
    ) {
        fun describe() = "$startMs-$endMs ms of the clip, noise floor ${FeatureGrade.f1(floorDb.toDouble())} dB -> " +
            "speech gate ${FeatureGrade.f1(CueAudio.gateDb(floorDb).toDouble())} dB, pause before " +
            "${gapBeforeMs?.let { "$it ms" } ?: "none"}, silences inside: " +
            (if (inside.isEmpty()) "none" else inside.joinToString { "${it.startMs}-${it.endMs} ms" }) +
            if (heard == null) "; NOT stored (heard nothing, or ambient's text filters drop it)" else "; line: $line"
    }

    /**
     * The clip through the live path: fed frame by frame to ambient's own [Segmenter] (then enough
     * silence to close the last utterance, as a quiet room would), each utterance with the noise
     * floor it was cut against; the pause before it ([TurnGaps]) and inside it
     * ([TranscriptCueWorker.pauses]) as the service and the cue worker measure them; whisper with
     * timing and the ambient [prompt]; ambient's text filters ([AmbientListenService.kept]); the
     * stored body ([CueTags.body]). Clip ms stand in for the wall clock (capture reads in real
     * time). An utterance live would not store gets no line (line = "") but still moves the pause
     * clock, as live.
     */
    private fun cutAndTag(handle: Long, clip: ShortArray, prompt: String): List<LiveUtterance> {
        val sr = AmbientConfig.SAMPLE_RATE
        val cut = ArrayList<Triple<ShortArray, Long, Float>>()
        var fedMs = 0L
        lateinit var seg: Segmenter
        seg = Segmenter { u, _ -> cut += Triple(u, fedMs, seg.noiseFloorDb) }
        var i = 0
        while (i < clip.size) {
            val end = minOf(i + AmbientConfig.FRAME, clip.size)
            fedMs = end * 1000L / sr
            seg.push(clip.copyOfRange(i, end))
            i = end
        }
        val quiet = ShortArray(AmbientConfig.FRAME)
        repeat(AmbientConfig.HANGOVER_MS / AmbientConfig.FRAME_MS + 2) { fedMs += AmbientConfig.FRAME_MS; seg.push(quiet) }

        val gaps = TurnGaps()
        val out = ArrayList<LiveUtterance>()
        for ((u, endMs, floor) in cut) {
            val db = CueAudio.frameDb(u)
            val audioMs = u.size * 1000L / sr
            val gap = gaps.next(db, floor, endMs, audioMs)
            val ur = ambientTranscribe(handle, u, prompt, timing = true)?.takeIf { AmbientListenService.kept(it) }
            val inside = TranscriptCueWorker.pauses(db, CueAudio.gateDb(floor), ur?.vad.orEmpty())
            val line = if (ur == null) ""
                else CueTags.body(ur.text, ur.words, inside, null, listOfNotNull(gap?.let { CueTags.pause(it) }))
            out += LiveUtterance(endMs - audioMs, endMs, floor, gap, inside, line, ur?.text)
        }
        return out
    }

    /** A whisper context like ambient listening's (Silero VAD when present) for [block], freed
     *  after it; null when the speech model won't load. */
    private suspend fun <T> ambientWhisper(ctx: Context, block: suspend (Long) -> T): T? {
        val dir = ctx.filesDir
        val vad = File(dir, AmbientConfig.VAD_MODEL).let { if (it.exists()) it.absolutePath else "" }
        val handle = WhisperNative.nativeInit(File(dir, AmbientConfig.ASR_MODEL).absolutePath, vad)
        if (handle == 0L) return null
        return try { block(handle) } finally { WhisperNative.nativeFree(handle) }
    }

    /** whisper on [pcm] with [prompt], on the fast cores (the LLM is idle during the check). */
    private fun ambientTranscribe(handle: Long, pcm: ShortArray, prompt: String, timing: Boolean): WhisperNative.Result? {
        val topo = CpuTopology.current
        return WhisperNative.transcribe(handle, pcm, if (topo.llmCpus.isEmpty()) 4 else minOf(4, topo.llmCpus.size),
            prompt, topo.llmCpus.toIntArray(), timing)
    }

    /** Speaker tags: the setting, the voiceprint model, and whether one voice matches itself. */
    private suspend fun speakerTags(env: ProbeEnv): List<Result> {
        val ctx = env.ctx
        val on = AmbientConfig(ctx).speakerTags
        val model = com.agent.ultra.listen.VoicePrints.downloaded(ctx)
        val head = "setting ${if (on) "on" else "off"}, voiceprint model ${if (model) "present" else "missing"}"
        if (!model) return listOf(Result("Speaker tags", Verdict.MISSING, head,
            "record your voice in Settings to download the voiceprint model and turn speaker tags on"))
        if (!(LocalVoice.available && LocalVoice.voicePresent(ctx))) return listOf(Result("Speaker tags", Verdict.WARN,
            "$head; same-voice check not run", "needs Jarvis's voice to make test speech"))
        val a = speak16k(ctx, CUE_FIRST, 0, 0)?.first
        val b = speak16k(ctx, CUE_SECOND, 0, 0)?.first
        val ea = a?.let { com.agent.ultra.listen.VoicePrints.embed(ctx, it) }
        val eb = b?.let { com.agent.ultra.listen.VoicePrints.embed(ctx, it) }
        if (ea == null || eb == null) return listOf(Result("Speaker tags", Verdict.FAIL, "$head; voiceprint failed",
            "the voiceprint model did not produce a print"))
        val sim = com.agent.ultra.listen.VoiceMatch.cosine(ea, eb)
        val same = sim >= com.agent.ultra.listen.VoiceMatch.MATCH
        // Jarvis's voice has one speaker (LocalVoice always speaks as speaker 0), so telling two
        // people apart needs two real voices; only "same voice matches itself" is tested here.
        return listOf(Result("Speaker tags", if (same) Verdict.WARN else Verdict.FAIL,
            "$head; same voice twice: similarity ${FeatureGrade.f2(sim.toDouble())} (match at ${com.agent.ultra.listen.VoiceMatch.MATCH}) → " +
                if (same) "matched" else "NOT matched",
            if (same) "one voice is recognised as the same person; telling two people apart needs two real voices"
            else "the same voice was not recognised as the same person",
            listOf("two different sentences in Jarvis's one voice; a two-voice test needs real people")))
    }

    // ── 4. Memory search (sandbox) ─────────────────────────────────────────────────────────

    private const val SANDBOX_DB = "featurecheck-ambient.db"

    suspend fun memorySearch(env: ProbeEnv): List<Result> {
        val ctx = env.ctx
        val detail = mutableListOf<String>()
        val downloaded = SentenceEmbedder.downloaded(ctx)
        var embLoadMs = -1L
        val emb = if (!downloaded) null else {
            val wasLoaded = SentenceEmbedder.loaded() != null
            val t0 = System.currentTimeMillis()
            SentenceEmbedder.shared(ctx).also { embLoadMs = if (wasLoaded) 0 else ms(t0) }
        }
        detail += "embedder downloaded=${yn(downloaded)} loaded=${yn(emb != null)} placement=${emb?.placement ?: "-"}" +
            if (embLoadMs > 0) " load=${embLoadMs} ms" else ""

        // Meaning sanity: a paraphrase must be closer than an unrelated sentence.
        var cosNumbers = "cosine n/a"
        var cosOk = false
        if (emb != null) {
            val v = emb.embed(listOf(
                "Remind me to call the dentist on Thursday.",
                "I need to phone my dental clinic this Thursday.",
                "The football match ended two nil.",
            ))
            val para = MemoryVectors.cosine(v[0], v[1]).toDouble()
            val unrel = MemoryVectors.cosine(v[0], v[2]).toDouble()
            cosOk = para > unrel
            cosNumbers = "paraphrase ${FeatureGrade.f2(para)} vs unrelated ${FeatureGrade.f2(unrel)}"
            detail += "cosine: $cosNumbers"
        }

        // Sandbox: its own database file, filled with test lines, closed and deleted afterwards.
        ctx.deleteDatabase(SANDBOX_DB)                     // a run that crashed may have left one
        val box = AmbientMemory.sandbox(ctx, SANDBOX_DB)
        var top1 = 0; var top3 = 0
        var briefChars = 0
        val queryMs = mutableListOf<Long>()
        try {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            for (h in FeatureCases.HEARD) box.record(h.text, FeatureCases.heardTs(h, now, zone), 0.9f)
            detail += "sandbox \"$SANDBOX_DB\": ${FeatureCases.HEARD.size} synthetic lines (your real memory is not touched)"
            for (r in FeatureCases.RECALL) {
                val t0 = System.currentTimeMillis()
                val hits = MemoryRecall.relevant(ctx, r.query, now, limit = 5, store = box, sources = emptyList())
                queryMs += ms(t0)
                val rank = FeatureGrade.rankOf(hits.map { it.text }, r.expected)
                if (rank == 1) top1++
                if (rank != null && rank <= 3) top3++
                detail += "${if (rank != null && rank <= 3) "ok   " else "MISS "} [${r.how}] \"${r.query}\" → rank ${rank ?: "-"} " +
                    "(top: ${hits.firstOrNull()?.text?.take(50) ?: "nothing"})"
            }
            // MemoryBrief: a memory question gets a "What I remember" block, 2+2 gets none.
            val brief = if (com.agent.ultra.agent.MemoryBrief.wants(FeatureCases.BRIEF_MEMORY_QUESTION).search)
                MemoryRecall.brief(MemoryRecall.relevant(ctx, FeatureCases.BRIEF_MEMORY_QUESTION, now, store = box, sources = emptyList()))
            else ""
            briefChars = brief.length
            detail += "brief for \"${FeatureCases.BRIEF_MEMORY_QUESTION}\": ${brief.length} chars"
            brief.lines().take(4).forEach { detail += "  | $it" }
        } finally {
            box.close()
            ctx.deleteDatabase(SANDBOX_DB)
        }
        val n = FeatureCases.RECALL.size
        val avgMs = if (queryMs.isEmpty()) 0 else queryMs.sum() / queryMs.size
        val searchVerdict = FeatureGrade.worst(
            FeatureGrade.byScore(top3, n),
            if (emb == null) Verdict.WARN else if (cosOk) Verdict.PASS else Verdict.FAIL,
        )
        val search = Result("Memory search", searchVerdict,
            "top-1 $top1/$n, top-3 $top3/$n, $cosNumbers, ${avgMs} ms/query, embedder ${if (emb != null) "on" else "OFF"}",
            when {
                emb == null && !downloaded -> "meaning search model not downloaded yet: words and day only"
                emb == null -> "meaning search model would not load: words and day only"
                searchVerdict == Verdict.PASS -> "finds what was said by meaning, by words and by day"
                else -> "misses things that were said, or can't tell related from unrelated"
            }, detail)

        val decisions = FeatureCases.briefDecisions()
        val bOk = decisions.all { it.ok } && briefChars > 0
        val brief = Result("Memory brief", if (bOk) Verdict.PASS else Verdict.FAIL,
            "memory question → block ${if (briefChars > 0) "yes ($briefChars chars)" else "NO"}; \"2+2\" → " +
                (if (decisions[1].ok) "none" else "a block"),
            if (bOk) "memory is added to the prompt only for questions about the past"
            else "memory goes into the wrong prompts, or none comes back for a memory question",
            decisions.map { it.line() })
        return listOf(search, brief)
    }

    // ── 7. Voice in and out ────────────────────────────────────────────────────────────────

    const val VOICE_SENTENCE = "Please remind me to buy fresh milk and bread tomorrow evening. " +
        "The meeting with Sarah moved to the small room upstairs."
    const val WAKE_SENTENCE = "Hey Jarvis, what time is it?"

    /** Jarvis's own voice for [text] as 16 kHz PCM with [padMs] of silence around it, or null. */
    private suspend fun speak16k(ctx: Context, text: String, padBeforeMs: Int, padAfterMs: Int): Pair<ShortArray, Long>? {
        val t0 = System.currentTimeMillis()
        val audio = LocalVoice.generate(ctx, text) ?: return null
        val took = ms(t0)
        val pcm = FeatureGrade.resample(audio.samples, audio.sampleRate, AmbientConfig.SAMPLE_RATE)
        val pre = AmbientConfig.SAMPLE_RATE * padBeforeMs / 1000
        val post = AmbientConfig.SAMPLE_RATE * padAfterMs / 1000
        val out = ShortArray(pre + pcm.size + post)
        LocalVoice.toPcm16(pcm).copyInto(out, pre)
        return out to took
    }

    suspend fun voice(env: ProbeEnv): List<Result> {
        val ctx = env.ctx
        val ttsOk = LocalVoice.available && LocalVoice.voicePresent(ctx)
        val sttOk = WhisperNative.available && LocalWhisper.modelPresent(ctx)
        if (!ttsOk || !sttOk) {
            val what = listOfNotNull(
                if (!ttsOk) "Jarvis voice (${if (!LocalVoice.available) "library missing" else "not downloaded"})" else null,
                if (!sttOk) "speech model (${if (!WhisperNative.available) "library missing" else "not downloaded"})" else null,
            ).joinToString(" and ")
            return listOf(
                Result("Voice round trip", Verdict.MISSING, "missing: $what", "can't test speaking and hearing without both models"),
                Result("Wake phrase", Verdict.MISSING, "missing: $what", "can't test the wake phrase without both models"),
            )
        }
        // 1) Speak a known sentence, hear it back, count wrong words.
        env.status("Voice: speaking a test sentence and transcribing it")
        val (pcm, ttsMs) = speak16k(ctx, VOICE_SENTENCE, 300, 500)
            ?: return listOf(Result("Voice round trip", Verdict.FAIL, "voice produced no audio", "Jarvis's voice failed to speak"))
        val audioSecs = (pcm.size - AmbientConfig.SAMPLE_RATE * 8 / 10).toDouble() / AmbientConfig.SAMPLE_RATE
        val t1 = System.currentTimeMillis()
        val heard = LocalWhisper.transcribe(ctx, pcm)
        val sttMs = ms(t1)
        val wer = FeatureGrade.wordErrorRate(VOICE_SENTENCE, heard?.text.orEmpty())
        val refWords = FeatureGrade.words(VOICE_SENTENCE).size
        val werV = when {
            heard == null -> Verdict.FAIL
            wer <= 0.10 -> Verdict.PASS
            wer <= 0.25 -> Verdict.WARN
            else -> Verdict.FAIL
        }
        val round = Result("Voice round trip", werV,
            "WER ${(wer * 100).toInt()}% of $refWords words, voice ${secs(ttsMs)} for ${FeatureGrade.f1(audioSecs)} s audio, whisper ${secs(sttMs)}",
            when (werV) {
                Verdict.PASS -> "Jarvis's voice is clear and the speech model hears it right"
                Verdict.WARN -> "mostly right; a few words are misheard"
                else -> "the speech model mishears much of what Jarvis says"
            },
            listOf("said:  $VOICE_SENTENCE", "heard: ${heard?.text ?: "(nothing)"} (confidence ${heard?.confidence ?: 0f})"))

        // 2) The wake phrase: acoustic gate (the "Hey Jarvis" model) and the text match after whisper.
        env.status("Voice: testing the wake phrase")
        val (wpcm, _) = speak16k(ctx, WAKE_SENTENCE, 2500, 1000)
            ?: return listOf(round, Result("Wake phrase", Verdict.FAIL, "voice produced no audio", "couldn't make the test audio"))
        var acoustic: Boolean? = null
        var maxScore = 0f
        var placement = ""
        if (wakeModelsPresent(ctx)) {
            val gate = WakeGate(ctx)
            try {
                if (gate.ready) {
                    placement = gate.placement
                    var fired = false
                    var i = 0
                    while (i < wpcm.size) {
                        val end = minOf(i + WakeGate.CHUNK, wpcm.size)
                        if (gate.accept(wpcm.copyOfRange(i, end))) fired = true
                        maxScore = maxOf(maxScore, gate.lastScore)
                        i = end
                    }
                    acoustic = fired
                }
            } finally { gate.close() }
        }
        val wakeText = LocalWhisper.transcribe(ctx, wpcm)?.text.orEmpty()
        val name = AmbientConfig(ctx).wakeName
        val command = WakeFilter.addressedCommand(wakeText, name)
        val textWake = command != null
        // Measurement only (nothing is graded on it): the same audio through ambient listening's
        // decode, with its filler prompt and without it, to see whether the fillers cost the wake
        // words. Timing off with the name alone is exactly ambient's decode before the cues.
        env.status("Voice: the wake phrase with and without the filler prompt")
        val filler = ambientWhisper(ctx) { h ->
            listOf(
                Triple("filler prompt, timing on (ambient now)", true, ambientTranscribe(h, wpcm, WhisperNative.verbatimPrompt(name), true)),
                Triple("no filler (name only), timing on", false, ambientTranscribe(h, wpcm, name, true)),
                Triple("no filler (name only), timing off (ambient before the cues)", false, ambientTranscribe(h, wpcm, name, false)),
            )
        }.orEmpty()
        fun wakeIn(r: WhisperNative.Result?) = r != null && WakeFilter.addressedCommand(r.text, name) != null
        val fillerLines = filler.map { (label, _, r) ->
            "ambient decode, $label: wake ${if (wakeIn(r)) "yes" else "NO"}, confidence " +
                "${FeatureGrade.f2((r?.confidence ?: 0f).toDouble())}" +
                (if (r != null && r.confidence < AmbientConfig.MIN_TOKEN_P) " (below ${AmbientConfig.MIN_TOKEN_P}: ambient would drop it)" else "") +
                ", heard: ${r?.text ?: "(nothing)"}"
        }.ifEmpty { listOf("ambient decode with/without filler prompt: speech model did not load") }
        val fillerSummary = filler.firstOrNull { it.second }?.let { (_, _, withF) ->
            val without = filler.firstOrNull { !it.second }?.third
            "; ambient decode wake: filler prompt ${if (wakeIn(withF)) "yes" else "NO"}, without ${if (wakeIn(without)) "yes" else "NO"}"
        }.orEmpty()
        val wv = when {
            acoustic == true && textWake -> Verdict.PASS
            acoustic == true || textWake -> Verdict.WARN
            else -> Verdict.FAIL
        }
        val wake = Result("Wake phrase", wv,
            "acoustic ${when (acoustic) { true -> "fired"; false -> "did NOT fire"; null -> "n/a (wake models missing)" }} " +
                "(max score ${FeatureGrade.f2(maxScore.toDouble())}), text match ${if (textWake) "yes" else "NO"}$fillerSummary",
            when (wv) {
                Verdict.PASS -> "\"Hey Jarvis\" is caught both by sound and by the words"
                Verdict.WARN -> "\"Hey Jarvis\" is caught one way only"
                else -> "\"Hey Jarvis\" was not recognised"
            },
            listOf("said: $WAKE_SENTENCE", "heard: $wakeText", "command after wake: ${command ?: "-"}",
                "wake name=\"$name\" gate placement=${placement.ifBlank { "-" }}") + fillerLines)
        return listOf(round, wake)
    }

    // ── 3 + 6. Memory model (librarian) ────────────────────────────────────────────────────

    private suspend fun librarianReady(env: ProbeEnv, feature: String): Pair<LocalModelEngine?, Result?> {
        val lib = LocalModelEngine.librarian(env.ctx)
        if (!lib.modelPresent) return null to Result(feature, Verdict.MISSING, "not downloaded",
            "Settings → MEMORY MODEL → download it first")
        return lib to null
    }

    suspend fun librarian(env: ProbeEnv): List<Result> {
        val (lib, missing) = librarianReady(env, "Memory model (librarian)")
        if (lib == null) return listOf(missing!!, missing.copy(feature = "Librarian notes"))
        val detail = mutableListOf("model=${lib.modelLabel}")
        val wasLoaded = lib.loaded
        env.status("Memory model: loading")
        val t0 = System.currentTimeMillis()
        val loaded = lib.ensureLoaded()
        val loadMs = ms(t0)
        if (!loaded) {
            val r = Result("Memory model (librarian)", Verdict.FAIL, "did not load", "the memory model could not load (memory full, or it gave way to the main model)")
            return listOf(r, r.copy(feature = "Librarian notes", numbers = "not run"))
        }
        env.status("Memory model: a short answer")
        val t1 = System.currentTimeMillis()
        val ok = lib.complete(LocalPrompt.buildNoThink("You are a test.", "Reply with the word OK."), 8)
        val callMs = ms(t1)
        detail += "load ${if (wasLoaded) "(already loaded)" else secs(loadMs)}; complete(8 tokens) ${callMs} ms → \"${ok.getOrNull()?.let { oneLine(it, 40) } ?: ok.exceptionOrNull()?.message}\""
        val model = Result("Memory model (librarian)",
            if (ok.isSuccess) Verdict.PASS else Verdict.FAIL,
            "load ${if (wasLoaded) "already done" else secs(loadMs)}, short answer ${callMs} ms",
            if (ok.isSuccess) "the memory model loads and answers" else "the memory model loaded but failed to answer: ${ok.exceptionOrNull()?.message}",
            detail.toList())

        // Notes from a canned transcript, through the real librarian prompt and parser. Nothing is stored.
        env.status("Memory model: writing notes from a test conversation")
        val zone = ZoneId.systemDefault()
        val chunk = FeatureCases.notesTranscript(System.currentTimeMillis() - 3_600_000)
        val patterns = AmbientConfig(env.ctx).notePatterns
        val t2 = System.currentTimeMillis()
        val raw = lib.complete(LibrarianPrompt.build(chunk, zone, patterns), LibrarianPrompt.maxTokens(patterns))
        val notesMs = ms(t2)
        val text = raw.getOrNull()
            ?: return listOf(model, Result("Librarian notes", Verdict.FAIL, "model failed: ${raw.exceptionOrNull()?.message}", "no notes came out"))
        val parsed = NoteParser.parse(text, chunk)
        val found = FeatureCases.notesFound(parsed.notes)
        val hit = found.count { it.ok }
        val notes = Result("Librarian notes", FeatureGrade.byScore(hit, found.size, pass = 1.0, warn = 0.6),
            "$hit/${found.size} expected notes, ${parsed.notes.size} written, ${parsed.rejected} rejected, ${secs(notesMs)}",
            when (hit) {
                found.size -> "turns overheard talk into the right notes (person, plan with time, to-do)"
                0 -> "no useful notes from an easy conversation"
                else -> "some notes are missed"
            },
            listOf("prompt: patterns=${patterns} (your setting); not stored") + found.map { it.line() } +
                parsed.notes.map { "  note ${it.kind} \"${it.text}\" when=${it.whenPhrase ?: "-"}" } +
                listOf("raw: ${oneLine(text, 400)}"))
        return listOf(model, notes)
    }

    suspend fun patternNotes(env: ProbeEnv): List<Result> {
        val feature = "Pattern notes"
        val (lib, missing) = librarianReady(env, feature)
        if (lib == null) return listOf(missing!!)
        val zone = ZoneId.systemDefault()
        val chunk = FeatureCases.patternDialogue(System.currentTimeMillis() - 3_600_000)
        env.status("Pattern notes: reading a test dialogue")
        val t0 = System.currentTimeMillis()
        val raw = lib.complete(LibrarianPrompt.build(chunk, zone, patterns = true), LibrarianPrompt.maxTokens(true))
        val text = raw.getOrNull() ?: return listOf(Result(feature, Verdict.FAIL,
            "model failed: ${raw.exceptionOrNull()?.message}", "no observations came out"))
        val parsed = NoteParser.parse(text, chunk)
        var drafts = parsed.notes
        var rejected = parsed.rejected
        val bare = drafts.indices.filter { drafts[it].obs != null && drafts[it].obs?.reading == null }.take(ReadingPrompt.MAX_OBS)
        var rawReadings = ""
        if (bare.isNotEmpty()) {
            env.status("Pattern notes: possible readings")
            val list = bare.map { drafts[it].text to drafts[it].obs!! }
            lib.complete(ReadingPrompt.build(list), ReadingPrompt.MAX_TOKENS).getOrNull()?.let { r ->
                rawReadings = r
                val (found, rRej) = NoteParser.parseReadings(r, list.size)
                rejected += rRej
                val byDraft = found.mapKeys { bare[it.key] }
                drafts = drafts.mapIndexed { i, d -> byDraft[i]?.let { rd -> d.copy(obs = d.obs!!.copy(reading = rd)) } ?: d }
            }
        }
        val took = ms(t0)
        val obs = drafts.filter { it.obs != null }
        // What would be stored, exactly as the librarian renders it.
        val stored = obs.map { ObservationText.render(it.text, it.obs!!, zone) }
        val readings = obs.mapNotNull { it.obs?.reading }
        val hedged = readings.count { CueText.isHedged(it.hedge) }
        val keptBanned = stored.flatMap { FeatureGrade.banned(it) }.distinct()
        val rawBanned = (FeatureGrade.banned(text) + FeatureGrade.banned(rawReadings)).distinct()
        val v = when {
            keptBanned.isNotEmpty() || hedged < readings.size -> Verdict.FAIL
            obs.isEmpty() -> Verdict.WARN
            else -> Verdict.PASS
        }
        return listOf(Result(feature, v,
            "${obs.size} observations, ${readings.size} readings ($hedged hedged), banned words kept ${keptBanned.size}, " +
                "filtered out $rejected, ${secs(took)}",
            when {
                keptBanned.isNotEmpty() -> "a stored note contains a diagnosis or label: ${keptBanned.joinToString()}"
                hedged < readings.size -> "a reading is stated as fact instead of hedged"
                obs.isEmpty() -> "no patterns noted in a dialogue that has some (not wrong, just unhelpful)"
                else -> "notes how people talk, with quotes and hedged readings, no diagnoses"
            },
            listOf("banned words in the raw model output (removed before storing): ${rawBanned.ifEmpty { listOf("none") }.joinToString()}",
                "not stored") + stored.map { "  $it" } +
                listOf("raw: ${oneLine(text, 400)}", "raw readings: ${oneLine(rawReadings, 300)}")))
    }

    // ── 2. Thinker ─────────────────────────────────────────────────────────────────────────

    private fun thinker(env: ProbeEnv) = LocalModelEngine.shared(env.ctx)

    /** The Change-model list, for [KitReport.smallerModel]. */
    private fun presets() = LocalModelEngine.PRESETS.map { KitReport.Preset(it.label, it.fileName, it.approxMb, it.desktopOnly) }

    /** [engine]'s weights in MB: its preset's listed size, else the file on disk. */
    private fun modelMb(engine: LocalModelEngine): Int =
        LocalModelEngine.PRESETS.firstOrNull { it.fileName == engine.modelFileName }?.approxMb
            ?: (engine.modelFileSizeBytes / 1_048_576).toInt()

    /** The brain test's verdict line for the model in use ([KitReport.advice]). */
    fun modelAdvice(engine: LocalModelEngine, verdict: KitReport.Model, medianTps: Double?): String =
        KitReport.advice(verdict, engine.modelLabel, engine.modelFileName, modelMb(engine), medianTps, presets(),
            exclude = setOf(LocalModelEngine.LIBRARIAN_DEFAULT.fileName))

    private fun missingThinker(feature: String) =
        Result(feature, Verdict.MISSING, "not downloaded", "Settings → ON-DEVICE MODEL → download one first")

    suspend fun thinkerSpeed(env: ProbeEnv): List<Result> {
        val engine = thinker(env)
        val feature = "Thinker model"
        if (!engine.modelPresent) return listOf(missingThinker(feature))
        val wasLoaded = engine.loaded
        env.status("Thinker: loading ${engine.modelLabel}")
        val t0 = System.currentTimeMillis()
        if (!engine.ensureLoaded()) return listOf(Result(feature, Verdict.FAIL, "did not load", "the main model could not load (not enough free memory?)"))
        val loadMs = ms(t0)
        env.status("Thinker: speed on a fixed prompt")
        val prompt = if (engine.isThinkingModel) LocalPrompt.buildNoThink("You are a test.", "Count from 1 to 40, separated by spaces.")
            else "Count from 1 to 40, separated by spaces:\n1 2 3"
        var pieces = 0; var tFirst = 0L; var tLast = 0L
        val f0 = PageFaults.read()
        val t1 = System.currentTimeMillis()
        val r = engine.generate(prompt, maxTokens = 64) {
            tLast = System.currentTimeMillis(); if (pieces++ == 0) tFirst = tLast
        }
        val f1 = PageFaults.read()
        if (r.isFailure) return listOf(Result(feature, Verdict.FAIL, "generate failed: ${r.exceptionOrNull()?.message}", "the main model loaded but could not answer"))
        val prefillMs = if (tFirst > 0) tFirst - t1 else ms(t1)
        val tps = if (pieces > 1 && tLast > tFirst) (pieces - 1) * 1000.0 / (tLast - tFirst) else 0.0
        val faults = if (f0 >= 0 && f1 >= 0) f1 - f0 else -1
        val v = when {
            tps >= KitReport.COMFORT_TOK_PER_SEC && faults in 0..1000 -> Verdict.PASS
            tps >= 2.0 -> Verdict.WARN
            else -> Verdict.FAIL
        }
        return listOf(Result(feature, v,
            "load ${if (wasLoaded) "already done" else secs(loadMs)}, ${FeatureGrade.f1(tps)} tok/s, first token ${secs(prefillMs)}, majfaults $faults",
            when {
                v == Verdict.PASS -> "loads and writes at a comfortable reading speed"
                faults > 1000 -> "the model doesn't fit in free memory: it re-reads itself from storage (slow)"
                v == Verdict.WARN -> "works, but slower than comfortable reading speed"
                else -> "too slow to be useful on this phone"
            },
            listOfNotNull("model=${engine.modelLabel} file=${engine.modelFileSizeBytes / 1_048_576} MB ctx=${engine.contextSize} " +
                "thinking=${engine.isThinkingModel}", "tokens=$pieces thermal=${ThermalGuard.level}",
                // Slow or paging: a smaller model is the remedy for both. One 64-token sample, so a guide only.
                if (tps < KitReport.COMFORT_TOK_PER_SEC || faults > 1000)
                    "one short sample: " + modelAdvice(engine, KitReport.Model.TRY_SMALLER, tps.takeIf { it > 0 })
                else null)))
    }

    /** Think budget and answer room for the graded questions: modest, so 6 questions fit in minutes. */
    private const val THINK = 192
    private const val ANSWER = 160
    private const val QUESTION_TIMEOUT_MS = 150_000L
    /** One request through the real chat brain (routing, history, memory search, tools included). */
    private const val ASK_TIMEOUT_MS = 240_000L
    private const val CONFIRM_TIMEOUT_MS = 2 * 60_000L

    /** One question straight to the engine, the way the offline chat prompts it; null = timed out. */
    private suspend fun ask(engine: LocalModelEngine, question: String, history: List<LocalPrompt.Turn> = emptyList(), mirrored: Boolean = false): String? =
        withTimeoutOrNull(QUESTION_TIMEOUT_MS) {
            val r = if (engine.isThinkingModel) {
                val prompt = if (mirrored) FeatureCases.mirroredPrompt(history, question)
                    else LocalPrompt.build(LocalPrompt.OFFLINE_RULES, history, question)
                engine.generate(prompt, maxTokens = THINK + ANSWER + 32, thinkBudget = ThinkBudget(THINK, startsInThink = true))
            } else {
                engine.generate("You are Jarvis, a concise assistant on an offline Android phone. " +
                    "Answer briefly and honestly.\n\nUser: $question\nJarvis:", ANSWER)
            }
            r.getOrElse { "(error: ${it.message})" }
        }

    suspend fun thinkerQuality(env: ProbeEnv): List<Result> {
        val engine = thinker(env)
        val feature = "Thinker quality"
        if (!engine.modelPresent) return listOf(missingThinker(feature))
        val detail = mutableListOf("think budget $THINK tokens, answer $ANSWER, ${QUESTION_TIMEOUT_MS / 1000} s per question")
        var correct = 0
        val qs = FeatureCases.THINKER_QUESTIONS
        for ((i, q) in qs.withIndex()) {
            env.status("Thinker quality: question ${i + 1}/${qs.size}")
            val t0 = System.currentTimeMillis()
            val a = ask(engine, q.ask)
            val ok = a != null && FeatureGrade.passes(a, q.check)
            if (ok) correct++
            val line = "${if (ok) "ok   " else "WRONG"} Q${i + 1} (${secs(ms(t0))}) ${q.ask.take(60)} → ${a?.let { oneLine(FeatureGrade.visible(it), 80) } ?: "(timed out)"}"
            detail += line
            env.say("  $line")
        }
        return listOf(Result(feature, FeatureGrade.byScore(correct, qs.size),
            "$correct/${qs.size} correct (${FeatureGrade.pct(correct, qs.size)})",
            when (FeatureGrade.byScore(correct, qs.size)) {
                Verdict.PASS -> "gets simple checkable questions right"
                Verdict.WARN -> "gets about half of simple checkable questions right"
                else -> "gets most simple checkable questions wrong"
            }, detail))
    }

    suspend fun thinkerRegression(env: ProbeEnv): List<Result> {
        val engine = thinker(env)
        val feature = "Thinker no-copying (Q8/Q11)"
        if (!engine.modelPresent) return listOf(missingThinker(feature))
        // The real chat path when offline (what the person uses); otherwise the same prompt
        // assembly called directly (online, the chat would go to the cloud model).
        val real = !env.online()
        val detail = mutableListOf(if (real) "path: the real chat (airplane mode on)"
            else "path: offline prompt assembled as the chat does (phone is online; turn on airplane mode for the real chat path)")
        val history = mutableListOf<LocalPrompt.Turn>()
        suspend fun turn(q: String): String? {
            env.status("Thinker no-copying: \"${q.take(40)}\"")
            val a = if (real) withTimeoutOrNull(ASK_TIMEOUT_MS) { env.askBrain(q) } else ask(engine, q, history, mirrored = true)
            history += LocalPrompt.Turn(true, q)
            history += LocalPrompt.Turn(false, a.orEmpty())
            detail += "Q: ${q.take(60)} → ${a?.let { oneLine(FeatureGrade.visible(it), 160) } ?: "(timed out)"}"
            return a
        }
        if (real) env.newChat()
        val math = turn(FeatureCases.REGRESSION_MATH)
        val packing = turn(FeatureCases.REGRESSION_PACKING)
        val summary = turn(FeatureCases.REGRESSION_SUMMARY)
        val mathOk = math != null && FeatureGrade.passes(math, FeatureGrade.Check.Number("391"))
        val noLeak = packing != null && !FeatureCases.packingLeak(packing)
        val readPaste = summary != null && FeatureCases.summaryReadPaste(summary)
        val v = when {
            !noLeak || !readPaste -> Verdict.FAIL
            !mathOk -> Verdict.WARN
            else -> Verdict.PASS
        }
        return listOf(Result(feature, v,
            "391 in packing list: ${if (packing == null) "n/a" else if (noLeak) "no" else "YES"} · summary about the paste: ${yn(readPaste)} · " +
                "17×23 right: ${yn(mathOk)} · ${if (real) "real chat" else "assembled prompt"}",
            when {
                !noLeak -> "an earlier answer leaked into a new one (the Q8 bug)"
                !readPaste -> "the summary was not about the pasted text (the Q11 bug)"
                !mathOk -> "no copying, but 17×23 came out wrong"
                else -> "each answer is about its own question; nothing copied from earlier"
            }, detail))
    }

    // ── 8b. Offline phone control, live ────────────────────────────────────────────────────

    suspend fun offlineNavLive(env: ProbeEnv): List<Result> {
        val feature = "Offline phone control (live)"
        val ctx = env.ctx
        if (!thinker(env).modelPresent) return listOf(missingThinker(feature))
        val cam = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val torchId = cam.cameraIdList.firstOrNull {
            cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return listOf(Result(feature, Verdict.MISSING, "no flashlight", "this phone has no flashlight to test with"))
        if (env.online()) return listOf(Result(feature, Verdict.WARN, "skipped: phone is online",
            "turn on airplane mode and run the check again to test this"))
        val yes = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            env.confirm("Last part: Jarvis's offline brain will turn your FLASHLIGHT ON and then OFF, " +
                "through the real chat, to check that it can control the phone. Go ahead?")
        }
        if (yes != true) return listOf(Result(feature, Verdict.WARN,
            if (yes == null) "skipped: no answer in ${CONFIRM_TIMEOUT_MS / 60_000} min" else "skipped by you", "not measured"))
        val torch = MutableStateFlow<Boolean?>(null)
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == torchId) torch.value = enabled }
            override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == torchId) torch.value = null }
        }
        cam.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
        val detail = mutableListOf<String>()
        var startedOn = false
        try {
            delay(300)                                         // the callback reports the current state
            startedOn = torch.value == true
            if (startedOn) {
                // Start from off, or "turn on" would look verified without doing anything.
                detail += "flashlight was on before the test: switched off first"
                runCatching { cam.setTorchMode(torchId, false) }
                withTimeoutOrNull(3_000) { torch.first { it == false } }
            }
            detail += "flashlight before: ${if (torch.value == true) "on" else "off"}"
            env.newChat()
            suspend fun step(request: String, want: Boolean): Pair<Boolean, String> {
                env.status("Live: \"$request\"")
                val t0 = System.currentTimeMillis()
                // Already in the wanted state (the step before failed): nothing this step does can show.
                val already = torch.value == want
                val answer = withTimeoutOrNull(ASK_TIMEOUT_MS) { env.askBrain(request) } ?: "(no answer in ${ASK_TIMEOUT_MS / 1000} s)"
                val reached = !already && withTimeoutOrNull(5_000) { torch.first { it == want } } != null
                val took = ms(t0)
                val log = env.logcat(t0, listOf("UltraBrain", "UltraOfflineNav"))
                val tools = log.count { "LOCAL TOOL:" in it }
                val navSteps = log.count { Regex("step \\d+:").containsMatchIn(it) }
                val steps = tools + navSteps
                detail += "\"$request\": ${if (reached) "VERIFIED" else if (already) "NOT verified (it was already ${if (want) "on" else "off"})" else "NOT verified"} (flashlight ${if (torch.value == true) "on" else "off"}), " +
                    "$steps action(s) ($tools tool, $navSteps screen steps), ${secs(took)}"
                detail += "  answer: ${oneLine(answer, 160)}"
                log.filter { "LOCAL" in it || "OFFLINE NAV" in it || "step" in it }.take(6).forEach { detail += "  log: ${it.substringAfter("): ")}" }
                return reached to "${secs(took)}, $steps action(s)"
            }
            val (on, onNum) = step("turn on the flashlight", true)
            val (off, offNum) = step("turn off the flashlight", false)
            val ok = listOf(on, off).count { it }
            return listOf(Result(feature, when (ok) { 2 -> Verdict.PASS; 1 -> Verdict.WARN; else -> Verdict.FAIL },
                "on: ${if (on) "verified" else "NOT verified"} ($onNum) · off: ${if (off) "verified" else "NOT verified"} ($offNum)",
                when (ok) {
                    2 -> "the offline brain switched the flashlight on and off, and it really changed"
                    1 -> "only one of the two commands really changed the flashlight"
                    else -> "the offline brain did not change the flashlight"
                }, detail))
        } finally {
            // Leave the light as it was before the test (also after a Skip, Stop or timeout).
            val nowOn = torch.value == true
            cam.unregisterTorchCallback(cb)
            if (nowOn != startedOn) runCatching { cam.setTorchMode(torchId, startedOn) }
        }
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"
}
