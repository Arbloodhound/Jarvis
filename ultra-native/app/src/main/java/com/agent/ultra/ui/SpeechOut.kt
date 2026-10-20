package com.agent.ultra.ui

/** A [SpeechQueue] that speaks through this [Speaker] on the main thread, registered so
 *  [SpeechQueue.stopAll] reaches it. Unregister it ([SpeechQueue.unregister]) with the speaker. */
fun Speaker.queue(): SpeechQueue {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    return SpeechQueue.register(SpeechQueue(
        speak = { text, done -> speak(text, done) },
        cut = { stop() },
        post = { block -> main.post(block) },
    ))
}
