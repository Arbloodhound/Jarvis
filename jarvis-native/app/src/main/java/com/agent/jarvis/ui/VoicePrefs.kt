package com.agent.jarvis.ui

import android.content.Context
import com.agent.jarvis.agent.TalkStyle
import com.agent.jarvis.listen.VoiceCatalog

/**
 * Settings → JARVIS'S VOICE and HOW JARVIS TALKS (owner, 2026-10-07: "let me choose the voice …
 * more customization, more settings, but keep it compatible"). Every default is what Jarvis did
 * before, except the voice itself (Kokoro "George" instead of Piper "Norman").
 */
object VoicePrefs {
    private const val PREFS = "jarvis_settings"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Voice ────────────────────────────────────────────────────────────────

    /** The chosen voice: a [VoiceCatalog] id or [VoiceCatalog.PHONE]. Before this setting existed
     *  the only choice was the old "Jarvis's own voice" switch: off there means the phone's voice. */
    fun voiceId(c: Context): String =
        p(c).getString("voice_id", null)
            ?: if (p(c).getBoolean("own_voice", true)) VoiceCatalog.DEFAULT else VoiceCatalog.PHONE

    fun setVoiceId(c: Context, id: String) {
        p(c).edit().putString("voice_id", id).putBoolean("own_voice", id != VoiceCatalog.PHONE).apply()
    }

    /** Speaker [voiceId] uses inside a multi-voice model (kept per model). */
    fun speaker(c: Context, modelId: String): Int {
        val m = VoiceCatalog.byId(modelId) ?: return 0
        return VoiceCatalog.clampSpeaker(m, p(c).getInt("voice_sid_$modelId", m.defaultSpeaker))
    }

    fun setSpeaker(c: Context, modelId: String, sid: Int) { p(c).edit().putInt("voice_sid_$modelId", sid).apply() }

    const val MIN_SPEED = 0.6f
    const val MAX_SPEED = 1.6f

    /** Speaking speed, 1 = normal. Own voices and the phone's voice. */
    fun speed(c: Context): Float = p(c).getFloat("voice_speed", 1f).coerceIn(MIN_SPEED, MAX_SPEED)
    fun setSpeed(c: Context, v: Float) { p(c).edit().putFloat("voice_speed", v.coerceIn(MIN_SPEED, MAX_SPEED)).apply() }

    /** Pitch, 1 = normal. The phone's voice only (the own voices have no pitch control). */
    fun pitch(c: Context): Float = p(c).getFloat("voice_pitch", 1f).coerceIn(0.5f, 2f)
    fun setPitch(c: Context, v: Float) { p(c).edit().putFloat("voice_pitch", v.coerceIn(0.5f, 2f)).apply() }

    /** Which of the phone's installed voices (TextToSpeech Voice name); empty = the engine's default. */
    fun phoneVoice(c: Context): String = p(c).getString("phone_voice", "").orEmpty()
    fun setPhoneVoice(c: Context, name: String) { p(c).edit().putString("phone_voice", name).apply() }

    // ── How Jarvis talks ─────────────────────────────────────────────────────

    fun personality(c: Context): TalkStyle.Personality =
        TalkStyle.Personality.values().firstOrNull { it.name == p(c).getString("personality", null) } ?: TalkStyle.Personality.BUTLER
    fun setPersonality(c: Context, v: TalkStyle.Personality) { p(c).edit().putString("personality", v.name).apply() }

    fun answerLength(c: Context): TalkStyle.Length =
        TalkStyle.Length.values().firstOrNull { it.name == p(c).getString("answer_length", null) } ?: TalkStyle.Length.NORMAL
    fun setAnswerLength(c: Context, v: TalkStyle.Length) { p(c).edit().putString("answer_length", v.name).apply() }

    /** The style lines both brains get (personality and length), before the owner's own instructions. */
    fun styleInstructions(c: Context): String = TalkStyle.instructions(personality(c), answerLength(c))

    /** What Jarvis says to a bare "Hey Jarvis". Blank = say nothing (the chime alone). */
    fun wakeReply(c: Context): String = p(c).getString("wake_reply", "Yes?").orEmpty().trim().take(60)
    fun setWakeReply(c: Context, v: String) { p(c).edit().putString("wake_reply", v.trim().take(60)).apply() }

    /** "On it." when an answer takes more than a couple of seconds. */
    fun sayOnIt(c: Context): Boolean = p(c).getBoolean("say_on_it", true)
    fun setSayOnIt(c: Context, on: Boolean) { p(c).edit().putBoolean("say_on_it", on).apply() }

    /** The short beep when "Hey Jarvis" is heard. */
    fun wakeChime(c: Context): Boolean = p(c).getBoolean("wake_chime", true)
    fun setWakeChime(c: Context, on: Boolean) { p(c).edit().putBoolean("wake_chime", on).apply() }

    /** How long a spoken request may think before answering. */
    fun spokenThink(c: Context): TalkStyle.SpokenThink =
        TalkStyle.SpokenThink.values().firstOrNull { it.name == p(c).getString("spoken_think", null) } ?: TalkStyle.SpokenThink.INSTANT
    fun setSpokenThink(c: Context, v: TalkStyle.SpokenThink) { p(c).edit().putString("spoken_think", v.name).apply() }

    /** Speak an answer sentence by sentence while it's written (else all at the end). */
    fun speakAsWritten(c: Context): Boolean = p(c).getBoolean("speak_as_written", true)
    fun setSpeakAsWritten(c: Context, on: Boolean) { p(c).edit().putBoolean("speak_as_written", on).apply() }

    /** A bare "stop" (no "Hey Jarvis") stops a task in progress. */
    fun bareStop(c: Context): Boolean = p(c).getBoolean("bare_stop", true)
    fun setBareStop(c: Context, on: Boolean) { p(c).edit().putBoolean("bare_stop", on).apply() }

    /** Phone actions happen at once (the tool is picked without a hidden think); the result is said after. */
    fun actFirst(c: Context): Boolean = p(c).getBoolean("act_first", true)
    fun setActFirst(c: Context, on: Boolean) { p(c).edit().putBoolean("act_first", on).apply() }

    /** "Hey Jarvis" said while Jarvis talks cuts it off. */
    fun bargeIn(c: Context): Boolean = p(c).getBoolean("barge_in", true)
    fun setBargeIn(c: Context, on: Boolean) { p(c).edit().putBoolean("barge_in", on).apply() }
}
