package com.agent.jarvis.listen

import android.content.Context
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** One stored heard line waiting for its cues: its level per frame, whisper's timing and, only
 *  while a voiceprint may be taken from it, its audio. */
class CueJob(
    val rowid: Long,
    val ts: Long,
    val plain: String,
    /** dBFS per [AmbientConfig.FRAME] of the utterance ([CueAudio.frameDb]). */
    val db: FloatArray,
    /** The utterance's length. */
    val audioMs: Long,
    /** 16 kHz audio for the voiceprint; null when none will be taken (speaker tags off, model
     *  missing, or let go to keep the queue small: see [CueQueue]). */
    val pcm: ShortArray?,
    /** The ambient gate's noise floor when the utterance was cut (dBFS). */
    val floorDb: Float,
    val words: List<TimedWord>,
    val vad: List<Span>,
    /** Silence since the previous utterance ended, when it counts as a pause ([CueTags]). */
    val gapBeforeMs: Long?,
    /** Speaker tags were on when the line was heard. Off: it is stored with no speaker label
     *  ([CueTags.UNLABELLED]), even if they are turned on before the line is processed. */
    val speakerTags: Boolean,
) {
    fun withoutAudio(): CueJob = if (pcm == null) this
        else CueJob(rowid, ts, plain, db, audioMs, null, floorDb, words, vad, gapBeforeMs, speakerTags)
}

/**
 * The cue worker's backlog, oldest first. Pure, JVM-tested.
 *
 * At most [maxJobs] lines: over that the oldest is dropped (it keeps no cues; the librarian reads
 * its plain text). At most [maxSamples] of audio across them: over that the oldest lines' audio
 * is let go (they still get pauses and the other cues, but no voiceprint, so "[Unknown]"). The
 * newest line keeps its audio, however long. Without audio a line costs a few KB.
 */
class CueQueue(private val maxJobs: Int = MAX_JOBS, private val maxSamples: Long = MAX_SAMPLES) {
    private val q = ArrayDeque<CueJob>()
    private var samples = 0L

    /** Adds [job]; returns how many queued lines were dropped or lost their audio. */
    @Synchronized
    fun add(job: CueJob): Int {
        q.addLast(job); samples += job.pcm?.size ?: 0
        var lost = 0
        while (q.size > maxJobs) { samples -= q.removeFirst().pcm?.size ?: 0; lost++ }
        var i = 0
        while (samples > maxSamples && i < q.size - 1) {
            val j = q[i]
            if (j.pcm != null) { samples -= j.pcm.size; q[i] = j.withoutAudio(); lost++ }
            i++
        }
        return lost
    }

    @Synchronized
    fun poll(): CueJob? = q.removeFirstOrNull()?.also { samples -= it.pcm?.size ?: 0 }

    val size: Int @Synchronized get() = q.size

    /** Samples of audio held. */
    val audioSamples: Long @Synchronized get() = samples

    companion object {
        const val MAX_JOBS = 24
        /** 2 minutes of 16 kHz audio (~3.8 MB of shorts), as the transcription backlog. */
        const val MAX_SAMPLES = 120L * AmbientConfig.SAMPLE_RATE
    }
}

/**
 * Adds the transcript cues to stored heard lines, after the fact and off the capture/whisper
 * path: speaker voiceprint and label, pauses, best-effort overlap, rate and loudness against that
 * speaker's usual. Writes AmbientMemory.heard_tag; the plain row is never touched.
 *
 * Runs only while neither on-device model is busy (it waits on LocalModelEngine.anyBusy before
 * each line and between overlap windows). The queue is bounded ([CueQueue]): when it overflows
 * the oldest lines simply keep no cues (the librarian then reads their plain text), and only
 * lines a voiceprint may be taken from hold their audio.
 */
class TranscriptCueWorker(private val ctx: Context, private val mem: AmbientMemory, private val cfg: AmbientConfig) {
    private val queue = CueQueue()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var closed = false

    /** Queues [job]; its audio only when a voiceprint may be taken (speaker tags on, model present).
     *  Ignored once [close]d: nothing would process it. */
    fun offer(job: CueJob) {
        if (closed) return
        val keep = if (job.speakerTags && VoicePrints.downloaded(ctx)) job else job.withoutAudio()
        val lost = queue.add(keep)
        if (lost > 0) android.util.Log.i(TAG, "cue queue full: $lost older line(s) dropped or kept without audio")
        wake.trySend(Unit)
    }

    fun close() { closed = true; wake.close() }

    suspend fun run() {
        val busy = com.agent.jarvis.local.LocalModelEngine.anyBusy
        try {
            while (true) {
                currentCoroutineContext().ensureActive()   // a full queue must not outlive the service
                // wait before taking a line, so a waiting line's audio stays counted in the queue
                if (busy.value) busy.first { !it }
                val job = queue.poll()
                if (job == null) {
                    if (wake.receiveCatching().isClosed && queue.size == 0) break
                    continue
                }
                // Throwable, not Exception: this runs beside capture in one coroutineScope, so
                // anything escaping here (a native/linkage Error included) would stop listening.
                try { process(job) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { android.util.Log.w(TAG, "cues failed for a line: ${e.message}") }
            }
        } finally {
            VoicePrints.release()
        }
    }

    private fun process(j: CueJob) {
        val now = System.currentTimeMillis()
        // Off when the line was heard, or off now (no voiceprint once the owner has said no).
        val tags = j.speakerTags && cfg.speakerTags
        val db = j.db
        val gate = CueAudio.gateDb(j.floorDb)
        val pauses = pauses(db, gate, j.vad)
        val span = if (j.vad.isNotEmpty()) Span(j.vad.minOf { it.startMs }, j.vad.maxOf { it.endMs })
            else CueAudio.speechSpan(db, gate) ?: Span(0, j.audioMs)
        val speechMs = (span.ms - pauses.filter { it.startMs >= span.startMs && it.endMs <= span.endMs }.sumOf { it.ms })
            .coerceAtLeast(0)

        // Who: a voiceprint of this line's speech, matched against the voices heard before.
        var voice: Voice? = null
        var emb: FloatArray? = null
        var sim = 0f
        var fresh = false   // a voice made from this very line: its centroid already is this line
        var voices: List<Voice> = emptyList()
        val pcm = j.pcm
        if (tags) {
            if (!VoicePrints.downloaded(ctx)) VoicePrints.fetchInBackground(ctx)
            else if (pcm != null && speechMs >= VoiceMatch.MIN_SPEECH_MS) {
                emb = VoicePrints.embed(ctx, pcm, sample(span.startMs), sample(span.endMs))
                voices = mem.voices()
                when (val d = VoiceMatch.decide(voices, emb, speechMs)) {
                    is VoiceMatch.Decision.Match -> { voice = d.voice; sim = d.similarity }
                    VoiceMatch.Decision.New -> {
                        val e = emb!!
                        val evict = VoiceMatch.evict(voices)
                        val id = mem.addVoice(e, now, evict?.id)
                        voice = Voice(id, 0, null, false, VoiceMatch.normalize(e), 1, now)   // as addVoice stored it
                        voices = voices.filter { it.id != evict?.id }
                        sim = 1f; fresh = true
                    }
                    VoiceMatch.Decision.Unknown -> {}
                }
            }
        }

        val overlapAt = voice?.let { v -> pcm?.let { overlap(it, span, speechMs, v, voices) } }

        // How: rate and loudness against this speaker's own usual (none for "Unknown").
        val wordCount = if (j.words.isNotEmpty()) j.words.size else j.plain.split(Regex("\\s+")).count { it.isNotBlank() }
        val wps = CueTags.wordsPerSecond(wordCount, speechMs)
        val level = CueAudio.speechLevelDb(db, gate)?.toDouble()
        // Experimental prosody (Settings): pitch against this speaker's own usual. Needs the audio,
        // which is kept only while speaker tags are on.
        val hz = if (com.agent.jarvis.ui.JarvisPrefs.prosody(ctx)) pcm?.let { CueAudio.pitchHz(it, span)?.toDouble() } else null
        val lead = ArrayList<String>()
        j.gapBeforeMs?.let { lead += CueTags.pause(it) }
        val v = voice
        if (v != null) {
            wps?.let { CueTags.rate(v.rate, it) }?.let { lead += it }
            level?.let { CueTags.loudness(v.loud, it) }?.let { lead += it }
            hz?.let { CueTags.pitch(v.pitch, it) }?.let { lead += it }
            val (c, n) = if (!fresh && emb != null && VoiceMatch.shouldUpdate(v, sim)) VoiceMatch.update(v.centroid, v.n, emb)
                else v.centroid to v.n
            mem.updateVoice(v, c, n, now,
                wps?.let { v.rate.add(it) } ?: v.rate, level?.let { v.loud.add(it) } ?: v.loud,
                hz?.let { v.pitch.add(it) } ?: v.pitch)
        }

        val body = CueTags.body(j.plain, j.words, pauses, overlapAt, lead)
        // speaker tags off: no label at all, rather than "[Unknown]" on every line
        mem.putTag(j.rowid, j.ts, if (!tags) CueTags.UNLABELLED else v?.id ?: 0L, body)
    }

    /** Best effort, see [VoiceMatch.overlapWindow]: ms (utterance clock) where another known
     * voice seems to come in, or null. Long lines only; stops if a model gets busy. */
    private fun overlap(pcm: ShortArray, span: Span, speechMs: Long, v: Voice, voices: List<Voice>): Long? {
        if (speechMs < OVERLAP_MIN_SPEECH_MS || voices.size < 2) return null
        val busy = com.agent.jarvis.local.LocalModelEngine.anyBusy
        val starts = (span.startMs until span.endMs - WINDOW_MS / 2 step WINDOW_MS).take(MAX_WINDOWS)
        val sims = ArrayList<Pair<Float, Float>>()
        for (s in starts) {
            if (busy.value) return null
            val e = VoicePrints.embed(ctx, pcm, sample(s), sample(minOf(span.endMs, s + WINDOW_MS))) ?: return null
            val other = VoiceMatch.best(voices, e, except = v.id)?.second ?: 0f
            sims += VoiceMatch.cosine(v.centroid, e) to other
        }
        val i = VoiceMatch.overlapWindow(sims)
        return if (i < 0) null else starts[i]
    }

    private fun sample(ms: Long): Int = (ms * AmbientConfig.SAMPLE_RATE / 1000).toInt()

    companion object {
        private const val TAG = "JarvisAmbient"
        /** Silences shorter than this aren't considered, even joined. */
        private const val PART_MS = 300L
        private const val OVERLAP_MIN_SPEECH_MS = 4_000L

        /** The pauses in one utterance: quiet runs in [db] (frame dB, [gate] = speech threshold)
         *  joined with gaps between whisper's [vad] stretches, at least [CueTags.MIN_PAUSE_MS].
         *  Also called by the debug feature check, so it tests this exact step. */
        internal fun pauses(db: FloatArray, gate: Float, vad: List<Span>): List<Span> = CueAudio.union(
            CueAudio.silences(db, gate, PART_MS), CueAudio.vadGaps(vad, PART_MS), CueTags.MIN_PAUSE_MS,
        )
        private const val WINDOW_MS = 2_000L
        private const val MAX_WINDOWS = 6
    }
}
