package com.agent.jarvis.listen

import android.content.Context

/**
 * Ambient listening settings. Thresholds are constants (tune here); the
 * user-facing choices (on/off, wake name, retention) live in prefs.
 */
class AmbientConfig(context: Context) {
    private val p = context.getSharedPreferences("ambient", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    /** Pick something distinctive, 2–3 syllables, rarely said in normal speech. */
    var wakeName: String
        get() = p.getString("wake_name", "Jarvis") ?: "Jarvis"   // matches the acoustic gate's "hey jarvis"
        set(v) = p.edit().putString("wake_name", v.trim()).apply()

    var retentionDays: Int
        get() = p.getInt("retention_days", 90)
        set(v) = p.edit().putInt("retention_days", v).apply()

    var maxRows: Int
        get() = p.getInt("max_rows", 250_000)
        set(v) = p.edit().putInt("max_rows", v).apply()

    /** The librarian turns what was heard into notes while the phone charges, screen off. */
    var organiseMemory: Boolean
        get() = p.getBoolean("organise_memory", true)
        set(v) = p.edit().putBoolean("organise_memory", v).apply()

    /** What the owner types or says to Jarvis goes into memory too ("[Me] (to Jarvis) …"), so the
     *  memory model makes notes from it and memory search finds it. */
    var rememberChat: Boolean
        get() = p.getBoolean("remember_chat", true)
        set(v) = p.edit().putBoolean("remember_chat", v).apply()

    /** The librarian also notes observable conversation patterns (hedging, commitments, ...)
     *  with verbatim quotes. Only used while [organiseMemory] is on. */
    var notePatterns: Boolean
        get() = p.getBoolean("note_patterns", true)
        set(v) = p.edit().putBoolean("note_patterns", v).apply()

    /** Observations may get a hedged possible psychological reading (second short pass). Only
     *  used while [notePatterns] is on. */
    var patternReadings: Boolean
        get() = p.getBoolean("pattern_readings", true)
        set(v) = p.edit().putBoolean("pattern_readings", v).apply()

    /** Speaker tags in the transcript: voiceprints made and kept on the phone (biometric data,
     * of everyone heard), so off until the owner turns it on or records their voice in Settings.
     * Off = new lines carry no speaker label, only pause and non-speech cues (rate and loudness
     * are judged against a known speaker's usual), and the 40 MB model is never downloaded. */
    var speakerTags: Boolean
        get() = p.getBoolean("speaker_tags", true)
        set(v) = p.edit().putBoolean("speaker_tags", v).apply()

    /** The larger speech model ([ASR_SMALL_MODEL], 190 MB) for quieter and more distant speech.
     *  On by default; base is used until small has downloaded, and again if small can't keep up. */
    var accurateSpeech: Boolean
        get() = p.getBoolean("accurate_speech", true)
        set(v) = p.edit().putBoolean("accurate_speech", v).putBoolean("small_too_slow", false).apply()

    /** Small was measured slower than speech on this phone ([AsrSpeed]); base is used instead
     *  until "Accurate speech recognition" is switched on again. */
    var smallTooSlow: Boolean
        get() = p.getBoolean("small_too_slow", false)
        set(v) = p.edit().putBoolean("small_too_slow", v).apply()

    /** The speech model file to load now ([AsrSpeed.pick]). */
    fun asrModel(dir: java.io.File): String = AsrSpeed.pick(accurateSpeech, smallReady(dir), smallTooSlow)

    companion object {
        // Audio
        const val SAMPLE_RATE = 16_000
        const val FRAME = 512                 // 32 ms @ 16 kHz
        const val FRAME_MS = FRAME * 1000 / SAMPLE_RATE

        // Stage 1: energy gate (cheap, runs on every frame)
        const val GATE_ABOVE_FLOOR_DB = 8f    // speech must be this far above the room's noise floor
        const val GATE_ABS_MIN_DB = -62f      // and never quieter than this (dBFS)
        const val START_FRAMES = 3            // ~96 ms of loud audio to open a segment
        const val PRE_ROLL_FRAMES = 10        // keep ~320 ms before onset so first syllable isn't clipped
        const val HANGOVER_MS = 700           // silence that closes a segment
        const val MIN_UTTER_MS = 400          // shorter = cough/click, dropped
        const val MAX_UTTER_MS = 28_000       // whisper window is 30 s
        const val COMMAND_MAX_MS = 9_000      // a spoken command after the wake word

        // Stage 2: whisper.cpp built-in Silero VAD drops non-speech inside segments.
        // Stage 3: text filters before anything is stored
        const val MIN_TOKEN_P = 0.38f         // mean token probability; below = likely hallucination
        const val MIN_WORDS = 2

        // Wake / response
        const val FOLLOWUP_MS = 8_000L        // after a bare "Jarvis", the next utterance is the command
        const val ECHO_GUARD_MS = 600L        // ignore mic until TTS ends + this

        // Compute
        const val WHISPER_THREADS = 2         // leave the rest for the LLM

        // Context injected into a request
        const val RECENT_WINDOW_MS = 15 * 60_000L
        const val CONTEXT_BUDGET_CHARS = 1_500

        // Models (verify URLs/versions before release)
        const val ASR_MODEL = "ggml-base.en-q5_1.bin"
        const val ASR_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin"
        /** whisper small.en, 5-bit: better than base on quiet, distant and accented speech, about 3x
         *  the compute. */
        const val ASR_SMALL_MODEL = "ggml-small.en-q5_1.bin"
        const val ASR_SMALL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-q5_1.bin"
        const val ASR_SMALL_BYTES = 190_098_681L
        fun smallReady(dir: java.io.File) = java.io.File(dir, ASR_SMALL_MODEL).length() == ASR_SMALL_BYTES
        /** Fetches small (resumable); blocking, so call it off the main thread. */
        fun fetchSmall(dir: java.io.File) =
            ModelFetcher.ensure(dir, ASR_SMALL_MODEL, ASR_SMALL_URL, ASR_SMALL_BYTES, exactBytes = ASR_SMALL_BYTES)
        const val VAD_MODEL = "ggml-silero-v5.1.2.bin"
        const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"
    }
}
