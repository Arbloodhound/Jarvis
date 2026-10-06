package com.agent.ultra.listen

import android.content.Context
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** One stored heard line waiting for its cues: its audio and whisper's timing. */
class CueJob(
    val rowid: Long,
    val ts: Long,
    val plain: String,
    val pcm: ShortArray,
    /** The ambient gate's noise floor when the utterance was cut (dBFS). */
    val floorDb: Float,
    val words: List<TimedWord>,
    val vad: List<Span>,
    /** Silence since the previous utterance ended, when it counts as a pause ([CueTags]). */
    val gapBeforeMs: Long?,
)

/**
 * Adds the transcript cues to stored heard lines, after the fact and off the capture/whisper
 * path: speaker voiceprint and label, pauses, best-effort overlap, rate and loudness against that
 * speaker's usual. Writes AmbientMemory.heard_tag; the plain row is never touched.
 *
 * Runs only while neither on-device model is busy (it waits on LocalModelEngine.anyBusy before
 * each line and between overlap windows). The queue is bounded: when it overflows the oldest
 * lines simply keep no cues (the librarian then reads their plain text).
 */
class TranscriptCueWorker(private val ctx: Context, private val mem: AmbientMemory, private val cfg: AmbientConfig) {
    private val jobs = Channel<CueJob>(QUEUE, BufferOverflow.DROP_OLDEST)

    fun offer(job: CueJob) { jobs.trySend(job) }

    fun close() { jobs.close() }

    suspend fun run() {
        val busy = com.agent.ultra.local.LocalModelEngine.anyBusy
        try {
            for (job in jobs) {
                currentCoroutineContext().ensureActive()   // a full queue must not outlive the service
                if (busy.value) busy.first { !it }
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
        val db = CueAudio.frameDb(j.pcm)
        val gate = CueAudio.gateDb(j.floorDb)
        val pauses = pauses(db, gate, j.vad)
        val span = if (j.vad.isNotEmpty()) Span(j.vad.minOf { it.startMs }, j.vad.maxOf { it.endMs })
            else CueAudio.speechSpan(db, gate) ?: Span(0, j.pcm.size * 1000L / AmbientConfig.SAMPLE_RATE)
        val speechMs = (span.ms - pauses.filter { it.startMs >= span.startMs && it.endMs <= span.endMs }.sumOf { it.ms })
            .coerceAtLeast(0)

        // Who: a voiceprint of this line's speech, matched against the voices heard before.
        var voice: Voice? = null
        var emb: FloatArray? = null
        var sim = 0f
        var fresh = false   // a voice made from this very line: its centroid already is this line
        var voices: List<Voice> = emptyList()
        if (cfg.speakerTags) {
            if (!VoicePrints.downloaded(ctx)) VoicePrints.fetchInBackground(ctx)
            else if (speechMs >= VoiceMatch.MIN_SPEECH_MS) {
                emb = VoicePrints.embed(ctx, j.pcm, sample(span.startMs), sample(span.endMs))
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

        val overlapAt = voice?.let { v -> overlap(j, span, speechMs, v, voices) }

        // How: rate and loudness against this speaker's own usual (none for "Unknown").
        val wordCount = if (j.words.isNotEmpty()) j.words.size else j.plain.split(Regex("\\s+")).count { it.isNotBlank() }
        val wps = CueTags.wordsPerSecond(wordCount, speechMs)
        val level = CueAudio.speechLevelDb(db, gate)?.toDouble()
        val lead = ArrayList<String>()
        j.gapBeforeMs?.let { lead += CueTags.pause(it) }
        val v = voice
        if (v != null) {
            wps?.let { CueTags.rate(v.rate, it) }?.let { lead += it }
            level?.let { CueTags.loudness(v.loud, it) }?.let { lead += it }
            val (c, n) = if (!fresh && emb != null && VoiceMatch.shouldUpdate(v, sim)) VoiceMatch.update(v.centroid, v.n, emb)
                else v.centroid to v.n
            mem.updateVoice(v, c, n, now,
                wps?.let { v.rate.add(it) } ?: v.rate, level?.let { v.loud.add(it) } ?: v.loud)
        }

        val body = CueTags.body(j.plain, j.words, pauses, overlapAt, lead)
        mem.putTag(j.rowid, j.ts, v?.id ?: 0L, body)
    }

    /** Best effort, see [VoiceMatch.overlapWindow]: ms (utterance clock) where another known
     * voice seems to come in, or null. Long lines only; stops if a model gets busy. */
    private fun overlap(j: CueJob, span: Span, speechMs: Long, v: Voice, voices: List<Voice>): Long? {
        if (speechMs < OVERLAP_MIN_SPEECH_MS || voices.size < 2) return null
        val busy = com.agent.ultra.local.LocalModelEngine.anyBusy
        val starts = (span.startMs until span.endMs - WINDOW_MS / 2 step WINDOW_MS).take(MAX_WINDOWS)
        val sims = ArrayList<Pair<Float, Float>>()
        for (s in starts) {
            if (busy.value) return null
            val e = VoicePrints.embed(ctx, j.pcm, sample(s), sample(minOf(span.endMs, s + WINDOW_MS))) ?: return null
            val other = VoiceMatch.best(voices, e, except = v.id)?.second ?: 0f
            sims += VoiceMatch.cosine(v.centroid, e) to other
        }
        val i = VoiceMatch.overlapWindow(sims)
        return if (i < 0) null else starts[i]
    }

    private fun sample(ms: Long): Int = (ms * AmbientConfig.SAMPLE_RATE / 1000).toInt()

    companion object {
        private const val TAG = "UltraAmbient"
        private const val QUEUE = 24
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
