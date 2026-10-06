package com.agent.ultra.ui

import android.content.Context

/** App-level preferences that are not provider credentials. */
object UltraPrefs {
    private const val PREFS = "ultra_settings"
    private const val K_SPEAK = "speak_answers"

    /** Speak answers aloud in the chat screen. The voice session always
     * speaks — that is the point of it — so this covers typed use only. */
    fun speakAnswers(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_SPEAK, false)

    private const val K_OWN_SPEECH = "own_speech"

    /** Recognise speech with Jarvis's own on-phone whisper model instead of Android's recognizer
     * (Google's, on a Pixel). On by default: works offline and on a de-Googled phone. */
    fun ownSpeech(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_OWN_SPEECH, true)

    fun setOwnSpeech(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_OWN_SPEECH, on).apply()
    }

    private const val K_OWN_VOICE = "own_voice"

    /** Speak with Jarvis's own on-phone voice instead of Android's TextToSpeech (Google's, on a
     * Pixel). On by default: works offline and on a de-Googled phone. */
    fun ownVoice(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_OWN_VOICE, true)

    fun setOwnVoice(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_OWN_VOICE, on).apply()
    }

    private const val K_PRELOAD = "preload_brain"

    /** Load the on-device brain in the background when Jarvis opens / ambient listening starts,
     * so the first answer doesn't wait for the model to load. On by default. */
    fun preloadBrain(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_PRELOAD, true)

    fun setPreloadBrain(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_PRELOAD, on).apply()
    }

    private const val K_RESIDENT = "keep_brain_in_memory"

    /** Load the on-device brain fully into Jarvis's own memory (no mapped file Android can drop
     * pages of). On by default; the engine still maps the file when memory is short. */
    fun keepBrainInMemory(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_RESIDENT, true)

    fun setKeepBrainInMemory(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_RESIDENT, on).apply()
    }

    private const val K_THINKING_DEPTH = "thinking_depth"

    /** How long the on-device brain may think before answering (WaitBudget.Depth).
     * BALANCED by default. */
    fun thinkingDepth(context: Context): com.agent.ultra.agent.WaitBudget.Depth =
        com.agent.ultra.agent.WaitBudget.depthOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_THINKING_DEPTH, null))

    fun setThinkingDepth(context: Context, depth: com.agent.ultra.agent.WaitBudget.Depth) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_THINKING_DEPTH, depth.name).apply()
    }

    private const val K_SKIP_CONFIRM = "skip_confirm_when_asked"

    /** Don't show the "are you sure?" card for a commitment the person asked for in their own
     * words (option B, see agent/DirectRequest: money, deletion, new recipients still ask). On. */
    fun skipConfirmWhenAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_SKIP_CONFIRM, true)

    fun setSkipConfirmWhenAsked(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_SKIP_CONFIRM, on).apply()
    }

    private const val K_NOTIF = "capture_notifications"
    private const val K_PERSONAL = "allow_personal_data"

    /**
     * Whether the agent may touch messages, contacts and location.
     *
     * Off by default. These tools reach Android's own providers directly, so
     * the app-access list does not cover them — that list gates what the
     * accessibility service can see on screen, and reading the SMS database
     * never goes near a screen. On a phone that takes real calls and texts,
     * the default has to be no.
     */
    fun allowPersonalData(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_PERSONAL, false)

    fun setAllowPersonalData(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_PERSONAL, on).apply()
    }

    /** Whether the notification listener keeps a rolling log on disk. Off by
     * default: it runs whether or not a task is active, so it is collection
     * rather than perception, and it catches message previews and one-time
     * codes. notification_read simply returns nothing when this is off. */
    fun captureNotifications(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_NOTIF, false)

    fun setCaptureNotifications(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_NOTIF, on).apply()
    }

    fun setSpeakAnswers(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_SPEAK, on).apply()
    }

    private const val K_SYSTEM_TRIGGERS = "system_triggers"

    fun systemTriggers(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_SYSTEM_TRIGGERS, false)

    fun setSystemTriggers(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_SYSTEM_TRIGGERS, on).apply()
    }

    private const val K_SMS_CODE = "auto_extract_sms_code"

    /** Auto-copy verification codes from incoming SMS to the clipboard. Off
     *  by default: this reads message content as it arrives, which is
     *  standing collection. Requires the notification listener. */
    fun autoExtractSmsCode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_SMS_CODE, false)

    fun setAutoExtractSmsCode(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_SMS_CODE, on).apply()
    }

    private const val K_SCAM = "scam_shield"

    /** Warn when an incoming message looks like a scam, and hold its numbers and
     *  links behind a confirm. ON by default, unlike the code auto-copy: it keeps
     *  nothing on disk (the flagged targets live in memory for 72 h) and the people
     *  it protects most are the least likely to find a switch. Needs the
     *  notification listener. */
    fun scamShield(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_SCAM, true)

    fun setScamShield(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_SCAM, on).apply()
    }

    private const val K_DOWNLOADS_CARD_DISMISSED = "downloads_card_dismissed"

    /** The chat's "Jarvis needs X GB of downloads" card was closed; Settings → DOWNLOADS still lists it all. */
    fun downloadsCardDismissed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_DOWNLOADS_CARD_DISMISSED, false)

    fun setDownloadsCardDismissed(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_DOWNLOADS_CARD_DISMISSED, on).apply()
    }
}
