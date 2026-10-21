package com.agent.jarvis.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.IOException

/**
 * Runs [block] (the blocking execute and body read of [call]) and cancels [call] if the calling
 * coroutine is cancelled meanwhile (Stop, or a timeout around it). A blocking OkHttp call ignores
 * coroutine cancellation. The cancelled call's IOException comes out as the caller's
 * CancellationException; a call that fails by itself throws as before.
 */
internal suspend fun <T> cancelOnStop(call: Call, block: () -> T): T {
    try {
        return coroutineScope {
            // Unconfined: the cancel runs on the thread that cancels, not after a wait for a free
            // IO thread. Cancelling a call that already finished does nothing.
            val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try { block() } finally { watcher.cancel() }
        }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw e
    }
}
