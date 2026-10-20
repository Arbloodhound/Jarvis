package com.agent.jarvis.ui

import android.content.Context

/** App-level preferences that are not provider credentials. */
object JarvisPrefs {
    private const val PREFS = "ultra_settings"

    private const val K_OWNER_NAME = "owner_name"
    private const val K_INSTRUCTIONS = "custom_instructions"
    /** Longest custom instructions kept: they go into every prompt, and prompt tokens are prefill time. */
    const val MAX_INSTRUCTIONS = 1500

    /** The owner's name (Settings → PEOPLE): Jarvis is told it, and "[Me]" in transcripts is them. Blank = not set. */
    fun ownerName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_OWNER_NAME, "").orEmpty().trim()

    fun setOwnerName(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_OWNER_NAME, name.replace(Regex("[\\[\\]\\n]"), " ").trim().take(40)).apply()
    }

    /** The owner's own instructions (Settings → SYSTEM PROMPT), added to both brains' system prompts. */
    fun customInstructions(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_INSTRUCTIONS, "").orEmpty().trim()

    fun setCustomInstructions(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_INSTRUCTIONS, text.trim().take(MAX_INSTRUCTIONS)).apply()
    }
    private const val K_SPEAK = "speak_answers"

    /** Speak answers aloud in the chat screen. The voice session always
     * speaks — that is the point of it — so this covers typed use only. On by default since
     * 2026-10-07 (the owner had never heard Jarvis speak). */
    fun speakAnswers(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_SPEAK, true)

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
        VoicePrefs.voiceId(context) != com.agent.jarvis.listen.VoiceCatalog.PHONE   // Settings → JARVIS'S VOICE

    fun setOwnVoice(context: Context, on: Boolean) {
        if (!on) VoicePrefs.setVoiceId(context, com.agent.jarvis.listen.VoiceCatalog.PHONE)
        else if (!ownVoice(context)) VoicePrefs.setVoiceId(context, com.agent.jarvis.listen.VoiceCatalog.DEFAULT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_OWN_VOICE, on).apply()
    }

    private const val K_OFFLINE_ONLY = "offline_only"
    private const val K_CLOUD_BRAIN = "cloud_brain"

    /** Offline only: no cloud brain, no web search (model downloads still work). Off by default,
     * and only the Settings switch changes it (the model picker used to flip it, which looked like
     * a toggle resetting itself: owner, 2026-10-19). Read through agent.NetPolicy. */
    fun offlineOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_OFFLINE_ONLY, false)

    /** Which brain answers: the on-device model (default) or the cloud model chosen in the chat's
     * model picker. Separate from [offlineOnly], which is a privacy switch on top of it. */
    fun cloudBrain(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_CLOUD_BRAIN, false)

    /** The small memory model picks tools and drives screens, the main model thinks and answers
     *  (owner, 2026-10-19: "a model that does all the actions"). On by default; falls back to the
     *  main model when the small one is not downloaded. */
    fun actionModel(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("action_model", true)

    /** After on-device tools run, the MAIN model writes the final reply from their results (one more
     *  model run, so slower). Off by default: results are then shown as the tools returned them. */
    fun mainReply(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("main_reply", false)

    fun setMainReply(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("main_reply", on).apply()
    }

    fun setActionModel(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("action_model", on).apply()
    }

    fun setCloudBrain(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_CLOUD_BRAIN, on).apply()
    }

    fun setOfflineOnly(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_OFFLINE_ONLY, on).apply()
    }

    private const val K_PROSODY = "prosody_experimental"

    /** Experimental prosody: pitch measured per heard line and tagged [higher]/[lower] against the
     * speaker's own usual (only with speaker tags on). Measurements, never verdicts. Off by default. */
    fun prosody(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_PROSODY, false)

    fun setProsody(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_PROSODY, on).apply()
    }

    private const val K_NOTIF_FILTER = "notif_filter"
    private const val K_QUIET_START = "notif_quiet_start"
    private const val K_QUIET_END = "notif_quiet_end"

    /** Notification filter (harness NotificationForwardingPolicy, Muse/OpenClaw): quiet hours and a
     * per-app burst limit in front of what Jarvis keeps of notifications. Off by default. The scam
     * shield and code copying still see every notification. */
    fun notificationFilter(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_NOTIF_FILTER, false)

    fun setNotificationFilter(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_NOTIF_FILTER, on).apply()
    }

    /** Quiet hours (local time, 0-23); Muse's defaults 22 → 7. */
    fun quietHours(context: Context): Pair<Int, Int> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        it.getInt(K_QUIET_START, 22) to it.getInt(K_QUIET_END, 7)
    }

    fun setQuietHours(context: Context, start: Int, end: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_QUIET_START, start.coerceIn(0, 23)).putInt(K_QUIET_END, end.coerceIn(0, 23)).apply()
    }

    private const val K_ONION = "onion_research"

    /** Onion research via Tor: .onion links in research are read through Orbot's SOCKS proxy
     * (net.TorProxy), with phishing/scam screening (agent.OnionSafety). Off by default; off means
     * onion links are skipped with one line. Offline only wins over it. */
    fun onionResearch(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ONION, false)

    fun setOnionResearch(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ONION, on).apply()
    }

    private const val K_DEEP = "deep_research_tor"

    /** Deep research over Tor: the bounded onion discovery crawl (research.Discovery), run only
     * when the owner says "deep research …" or "investigate …", and only with onion research on.
     * Off by default. */
    fun deepResearch(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_DEEP, false)

    fun setDeepResearch(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_DEEP, on).apply()
    }

    private const val K_ONION_KNOWN = "onion_known_good"

    /** The owner's own verified onion addresses, one "address [name]" per line. Empty by default;
     * trusted like the built-in directory (agent.OnionSafety.knownGood). */
    fun onionKnownGood(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_ONION_KNOWN, "").orEmpty()

    /** Stores only lines with a valid v3 address (agent.OnionSafety.cleanOwn); returns what was kept. */
    fun setOnionKnownGood(context: Context, text: String): String {
        val clean = com.agent.jarvis.agent.OnionSafety.cleanOwn(text)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_ONION_KNOWN, clean).apply()
        return clean
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

    private const val K_SPEED_BOOST = "speed_boost_draft"

    /** Speed boost: speculative decoding with the small draft model ([com.agent.jarvis.local.DraftModel])
     * beside the brain. Off by default; when on, it only takes effect once the draft is downloaded, and
     * the engine skips it when memory is short. Same answers either way. */
    fun speedBoost(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_SPEED_BOOST, false)   // off: on the Pixel it made decoding slower (6.3 vs 9.3 tok/s) and an answer came back cut short (Real-use check 2.6.28)

    fun setSpeedBoost(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_SPEED_BOOST, on).apply()
    }

    private const val K_THINKING_DEPTH = "thinking_depth"

    /** How long the on-device brain may think before answering (WaitBudget.Depth).
     * BALANCED by default. */
    fun thinkingDepth(context: Context): com.agent.jarvis.agent.WaitBudget.Depth =
        com.agent.jarvis.agent.WaitBudget.depthOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_THINKING_DEPTH, null))

    fun setThinkingDepth(context: Context, depth: com.agent.jarvis.agent.WaitBudget.Depth) {
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
