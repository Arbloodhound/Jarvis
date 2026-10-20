package com.agent.jarvis.net

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import org.torproject.jni.TorService

/**
 * Tor inside the app: the Guardian Project's tor-android (BSD-3; the same Tor library Orbot is
 * built on). Binding [TorService] starts tor in this process; unbinding stops it. Its SOCKS port is
 * 9050 when that is free, else one tor picks — [socksPort] says which. Nothing outside the app can
 * use it, and only [TorProxy] sends traffic through it (.onion only).
 *
 * Started only by the owner's "Connect to Tor" tap or the tor_connect tool (which checks the opt-in
 * setting first); never at app start.
 */
object EmbeddedTor {
    @Volatile private var service: TorService? = null
    @Volatile private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            TorProxy.embeddedPort = -1
        }
    }

    /** Binds (and so starts) the in-app tor. False when Android refused the bind. */
    @Synchronized fun start(ctx: Context): Boolean {
        if (bound) return true
        val app = ctx.applicationContext
        bound = try { app.bindService(Intent(app, TorService::class.java), conn, Context.BIND_AUTO_CREATE) } catch (_: Exception) { false }
        android.util.Log.i("UltraTor", "in-app tor bind=$bound")
        return bound
    }

    /** Stops the in-app tor (onion research falls back to Orbot, if that runs). */
    @Synchronized fun stop(ctx: Context) {
        if (!bound) return
        runCatching { ctx.applicationContext.unbindService(conn) }
        bound = false
        service = null
        TorProxy.embeddedPort = -1
        android.util.Log.i("UltraTor", "in-app tor stopped")
    }

    val started: Boolean get() = bound

    /** Tor's SOCKS port once it has one, else -1. */
    fun socksPort(): Int = runCatching { service?.socksPort }.getOrNull()?.takeIf { it > 0 } ?: -1

    /** Bootstrap progress 0-100 from tor's control port; -1 before it answers. */
    fun bootstrapPercent(): Int = runCatching { service?.getInfo("status/bootstrap-phase") }.getOrNull()
        ?.let { progressOf(it) } ?: -1

    /** Ready = bootstrapped and has a port; the port is then what [TorProxy.current] uses. */
    fun ready(): Boolean {
        val port = socksPort()
        val ok = port > 0 && bootstrapPercent() >= 100
        if (ok) TorProxy.embeddedPort = port
        return ok
    }

    /** Pure. "NOTICE BOOTSTRAP PROGRESS=85 TAG=..." → 85; null when there is no PROGRESS. */
    fun progressOf(phase: String): Int? = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull()
}
