package com.agent.ultra.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Starting Tor on request: Settings → "Connect to Tor" and the tor_connect tool (the non-screen
 * route; on screen, Orbot is just an app). Tor is Orbot's, the same SOCKS proxy [TorProxy] reads
 * .onion pages through. Orbot's public integration API: a START broadcast addressed to its package;
 * it answers with STATUS broadcasts (ON / STARTING / STOPPING / OFF / STARTS_DISABLED). Orbot
 * refuses starts from other apps unless its own "Allow background starts" is on — then Orbot is
 * opened for the owner to tap Start.
 *
 * Connected means the proxy port answers ([TorProxy.isAvailable]), not what a broadcast says.
 * This never turns on onion research: the tool checks that setting first (opt-in, off by default).
 */
object TorConnect {
    const val ORBOT = "org.torproject.android"
    const val TOR_BROWSER = "org.torproject.torbrowser"
    const val ACTION_START = "org.torproject.android.intent.action.START"
    const val ACTION_STATUS = "org.torproject.android.intent.action.STATUS"
    const val EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME"
    const val EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS"

    /** How long a connect waits for the proxy: a cold Tor start takes from seconds to a minute or two. */
    const val WAIT_MS = 60_000L

    enum class State { NOT_INSTALLED, OFF, STARTING, ON, STARTS_DISABLED }

    data class Outcome(val state: State, val ms: Long, val openedOrbot: Boolean = false)

    /** Pure. Orbot's status string as a [State]; null for one this doesn't know. */
    fun stateOf(status: String?): State? = when (status) {
        "ON" -> State.ON
        "STARTING" -> State.STARTING
        "OFF", "STOPPING" -> State.OFF
        "STARTS_DISABLED" -> State.STARTS_DISABLED
        else -> null
    }

    /** Pure. What the owner (and the model) is told. */
    fun describe(o: Outcome): String {
        val s = (o.ms + 500) / 1000
        return when (o.state) {
            State.ON -> if (o.ms == 0L) "Tor is already connected (Orbot, 127.0.0.1:${TorProxy.PORT})."
                else "Connected to Tor in $s s (Orbot, 127.0.0.1:${TorProxy.PORT})."
            State.STARTING -> "Still connecting to Tor after $s s. A cold start can take a minute or two — try again shortly."
            State.NOT_INSTALLED -> "Orbot isn't installed, and Tor needs it. Nothing was started."
            State.STARTS_DISABLED -> "Orbot doesn't let other apps start it (Orbot → Settings → \"Allow background starts\")." +
                if (o.openedOrbot) " I opened Orbot: tap Start." else ""
            State.OFF -> "Tor didn't start within $s s." + if (o.openedOrbot) " I opened Orbot: tap Start there." else ""
        }
    }

    /**
     * Pure (time and probes injected). Polls until the proxy answers ([available]), Orbot says its
     * starts are disabled, or [waitMs] passes. [status] is the latest STATUS broadcast, if any.
     */
    suspend fun await(
        available: suspend () -> Boolean,
        status: () -> State?,
        waitMs: Long = WAIT_MS,
        pollMs: Long = 1_000,
        now: () -> Long = System::currentTimeMillis,
    ): Outcome {
        val t0 = now()
        while (true) {
            val ms = now() - t0
            if (available()) return Outcome(State.ON, ms)
            if (status() == State.STARTS_DISABLED) return Outcome(State.STARTS_DISABLED, ms)
            if (ms >= waitMs) return Outcome(if (status() == State.STARTING) State.STARTING else State.OFF, ms)
            delay(pollMs)
        }
    }

    fun installed(ctx: Context, pkg: String = ORBOT): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    /** Quick status, no start: is the proxy answering right now? */
    suspend fun connected(proxy: TorProxy = TorProxy.shared): Boolean = withContext(Dispatchers.IO) { proxy.isAvailable() }

    /**
     * Asks Orbot to start Tor and waits for the proxy. [openOrbot]: when Orbot refuses or Tor
     * doesn't come up, open Orbot so the owner can tap Start.
     */
    suspend fun connect(ctx: Context, openOrbot: Boolean, waitMs: Long = WAIT_MS, proxy: TorProxy = TorProxy.shared): Outcome {
        if (!installed(ctx)) return Outcome(State.NOT_INSTALLED, 0)
        if (connected(proxy)) return Outcome(State.ON, 0)
        val last = java.util.concurrent.atomic.AtomicReference<State?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { stateOf(i?.getStringExtra(EXTRA_STATUS))?.let { last.set(it) } }
        }
        val app = ctx.applicationContext
        androidx.core.content.ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_STATUS),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        val out = try {
            app.sendBroadcast(Intent(ACTION_START).setPackage(ORBOT).putExtra(EXTRA_PACKAGE_NAME, app.packageName))
            await({ connected(proxy) }, { last.get() }, waitMs)
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
        android.util.Log.i("UltraTor", "connect: ${out.state} in ${out.ms} ms (orbot status=${last.get()})")
        if (out.state == State.ON || out.state == State.STARTING || !openOrbot) return out
        val opened = try {
            app.packageManager.getLaunchIntentForPackage(ORBOT)?.let {
                app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            } ?: false
        } catch (_: Exception) { false }
        return out.copy(openedOrbot = opened)
    }
}
