package com.agent.ultra.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Answers spoken aloud, on-device. Android's TextToSpeech engine is free,
 * offline once a voice is installed, and needs no permission.
 *
 * Init is asynchronous, so anything said before the engine is ready is held
 * and spoken on ready. Start and finish are logged (`UltraSpeak`) — that is
 * how speak-back gets verified on a machine that cannot hear the phone.
 */
class Speaker(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var failed = false
    private var pending: String? = null
    private var onDone: (() -> Unit)? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale.getDefault())
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.US)
                    }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Log.i(TAG, "SPEAK START id=$utteranceId")
                        }
                        override fun onDone(utteranceId: String?) {
                            Log.i(TAG, "SPEAK DONE id=$utteranceId")
                            onDone?.invoke()
                        }
                        @Deprecated("required by the base class")
                        override fun onError(utteranceId: String?) {
                            Log.w(TAG, "SPEAK ERROR id=$utteranceId")
                            onDone?.invoke()
                        }
                    })
                    ready = true
                    Log.i(TAG, "engine ready")
                    pending?.let { p -> pending = null; speak(p) }
                } else {
                    failed = true
                    Log.w(TAG, "engine init failed status=$status")
                }
            }
        } catch (e: Exception) {
            failed = true
            Log.w(TAG, "engine construction failed", e)
        }
    }

    /** Speak text. Held until the engine is ready if it is still starting. */
    fun speak(text: String, whenDone: (() -> Unit)? = null) {
        val clean = forSpeech(text)
        if (clean.isBlank()) { whenDone?.invoke(); return }
        onDone = whenDone
        if (failed) { Log.w(TAG, "cannot speak — engine unavailable"); whenDone?.invoke(); return }
        if (!ready) { pending = clean; return }
        val id = "ultra-" + System.currentTimeMillis()
        Log.i(TAG, "SPEAK QUEUE (${clean.length} chars) id=$id")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        pending = null
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "UltraSpeak"

        /** Strip what reads badly aloud: emoji, code fences, raw JSON, markdown. */
        fun forSpeech(raw: String): String = raw
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\{[^{}]*\"tool\"[\\s\\S]*?\\}"), " ")
            .replace(Regex("[*_`#>]"), "")
            .replace(Regex("https?://\\S+")) { m ->
                // Read domains, not full URLs — "google dot com", not the query string.
                Regex("https?://(?:www\\.)?([^/\\s]+)").find(m.value)
                    ?.groupValues?.get(1)?.replace(".", " dot ") ?: " a link "
            }
            .replace(Regex("[\\p{So}\\p{Cn}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)
    }
}
