package com.agent.ultra.listen

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

    companion object {
        // Audio
        const val SAMPLE_RATE = 16_000
        const val FRAME = 512                 // 32 ms @ 16 kHz
        const val FRAME_MS = FRAME * 1000 / SAMPLE_RATE

        // Stage 1: energy gate (cheap, runs on every frame)
        const val GATE_ABOVE_FLOOR_DB = 10f   // speech must be this far above the room's noise floor
        const val GATE_ABS_MIN_DB = -50f      // and never quieter than this (dBFS)
        const val START_FRAMES = 3            // ~96 ms of loud audio to open a segment
        const val PRE_ROLL_FRAMES = 10        // keep ~320 ms before onset so first syllable isn't clipped
        const val HANGOVER_MS = 700           // silence that closes a segment
        const val MIN_UTTER_MS = 400          // shorter = cough/click, dropped
        const val MAX_UTTER_MS = 28_000       // whisper window is 30 s

        // Stage 2: whisper.cpp built-in Silero VAD drops non-speech inside segments.
        // Stage 3: text filters before anything is stored
        const val MIN_TOKEN_P = 0.45f         // mean token probability; below = likely hallucination
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
        const val VAD_MODEL = "ggml-silero-v5.1.2.bin"
        const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"
    }
}
