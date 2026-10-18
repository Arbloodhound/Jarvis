package com.agent.ultra.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device speech-to-text. One-shot per start().
 *
 * `preferOffline` asks the platform recognizer (SODA on this device) to stay
 * on-device. If no offline voice pack is installed the platform falls back to
 * its network recognizer on its own; there is no way to force offline-only
 * without failing the request, so this is a preference, not a guarantee.
 */
class VoiceInput(
    private val context: Context,
    private val preferOffline: Boolean = true,
    private val onState: ((String) -> Unit)? = null,
    private val onError: ((String) -> Unit)? = null,
    /** Live words as they're recognized, before the final result (platform recognizer only). */
    private val onPartial: ((String) -> Unit)? = null,
    private val onResult: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private val own by lazy { com.agent.ultra.listen.LocalSpeech(context, onState, onError, onPartial, onResult) }

    fun start(): Boolean {
        // Jarvis's own whisper when the user prefers it (default: no Google), or when the phone has
        // no system recognizer at all (a de-Googled phone).
        if (UltraPrefs.ownSpeech(context) || !SpeechRecognizer.isRecognitionAvailable(context)) {
            stopInternal()
            return own.start()
        }
        return try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "speech recognition unavailable")
                onError?.invoke("Speech recognition is not available on this phone")
                return false
            }
            stopInternal()
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val best = texts?.firstOrNull()?.trim().orEmpty()
                    Log.i(TAG, "HEARD: \"$best\"")
                    // One-shot: release the recognizer before handing on, so a
                    // callback that starts listening again gets a fresh one.
                    releaseIf(sr)
                    if (best.isNotEmpty()) onResult(best)
                    else onError?.invoke("I didn't catch that")
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "error $error (${errorName(error)})")
                    releaseIf(sr)
                    onError?.invoke(errorName(error))
                }
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.i(TAG, "LISTENING")
                    onState?.invoke("listening")
                }
                override fun onBeginningOfSpeech() { onState?.invoke("hearing") }
                override fun onEndOfSpeech() { onState?.invoke("thinking") }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    // Live words while the user is still talking.
                    val best = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (best.isNotEmpty()) onPartial?.invoke(best)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (preferOffline) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            sr.startListening(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            onError?.invoke(e.message ?: "could not start listening")
            false
        }
    }

    fun stop() {
        own.stop()
        stopInternal()
    }

    /**
     * Stop recording now and use what was heard: the platform recognizer keeps processing and fires
     * its final onResults (unlike [stop], which throws the utterance away). The mic's "stop" button.
     */
    fun finish() {
        own.finish()
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    private fun releaseIf(sr: SpeechRecognizer) {
        if (recognizer !== sr) return
        try { sr.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun stopInternal() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
    }

    companion object {
        private const val TAG = "UltraVoice"

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_AUDIO -> "audio error"
            SpeechRecognizer.ERROR_CLIENT -> "client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission denied"
            SpeechRecognizer.ERROR_NETWORK -> "network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "recognizer disconnected"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language pack not installed"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannot check language support"
            else -> "error $code"
        }
    }
}
