package com.agent.jarvis.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Tor is built into the app ([EmbeddedTor]); when Orbot already runs on 9050 that is used instead.
 * Connected means bootstrapped (in-app) or the proxy port answering (Orbot).
 */
object TorConnect {
    const val ORBOT = "org.torproject.android"
    const val TOR_BROWSER = "org.torproject.torbrowser"

    /** How long a connect waits. */
    const val WAIT_MS = 90_000L

    enum class State { OFF, STARTING, ON, FAILED }

    data class Outcome(val state: State, val ms: Long, val via: String = IN_APP, val percent: Int = -1)

    const val IN_APP = "in-app Tor"
    const val VIA_ORBOT = "Orbot"

    /** Pure. Renders [Outcome] as a status string. */
    fun describe(o: Outcome): String {
        val s = (o.ms + 500) / 1000
        return when (o.state) {
            State.ON -> if (o.ms == 0L) "Tor is already connected (${o.via})." else "Connected to Tor in $s s (${o.via})."
            State.STARTING -> "Still connecting to Tor after $s s" + (if (o.percent >= 0) " (${o.percent}% bootstrapped)" else "") +
                ". A cold start can take a minute or two — try again shortly."
            State.FAILED -> "Couldn't start the in-app Tor (Android refused the service). Nothing was connected."
            State.OFF -> "Tor isn't running."
        }
    }

    /** Pure (time and probes injected). Polls until [ready], or [waitMs] passes. */
    suspend fun await(
        ready: suspend () -> Boolean,
        percent: () -> Int,
        waitMs: Long = WAIT_MS,
        pollMs: Long = 1_000,
        now: () -> Long = System::currentTimeMillis,
    ): Outcome {
        val t0 = now()
        while (true) {
            val ms = now() - t0
            if (ready()) return Outcome(State.ON, ms)
            if (ms >= waitMs) return Outcome(State.STARTING, ms, percent = percent())
            delay(pollMs)
        }
    }

    fun installed(ctx: Context, pkg: String = ORBOT): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    private fun orbotUp(): Boolean = TorProxy(port = TorProxy.PORT).isAvailable()

    /** Quick status, no start: which Tor answers right now, or null. */
    suspend fun connectedVia(): String? = withContext(Dispatchers.IO) {
        when {
            EmbeddedTor.started -> if (EmbeddedTor.ready()) IN_APP else null
            orbotUp() -> VIA_ORBOT
            else -> null
        }
    }

    suspend fun connected(): Boolean = connectedVia() != null

    /** Starts the in-app Tor (unless Orbot already serves) and waits until it is bootstrapped. */
    suspend fun connect(ctx: Context, waitMs: Long = WAIT_MS): Outcome {
        connectedVia()?.let { return Outcome(State.ON, 0, it) }
        if (!EmbeddedTor.start(ctx)) return Outcome(State.FAILED, 0)
        val out = withContext(Dispatchers.IO) { await({ EmbeddedTor.ready() }, { EmbeddedTor.bootstrapPercent() }, waitMs) }
        android.util.Log.i("JarvisTor", "connect: ${out.state} in ${out.ms} ms (bootstrap ${EmbeddedTor.bootstrapPercent()}%, port ${EmbeddedTor.socksPort()})")
        return out
    }
}
