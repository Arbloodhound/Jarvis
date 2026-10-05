package com.agent.ultra.agent

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

/**
 * Android side of the thermal policy. Polls the 10 s headroom forecast every 10 s
 * (the API returns NaN if called more than ~once a second) and listens for OS
 * status changes. Fail-open: if anything is unsupported, the level stays NORMAL.
 *
 *   ThermalGuard.start(context)          // once, e.g. Application.onCreate
 *   val lvl = ThermalGuard.level         // read anywhere, cheap
 *   ThermalGuard.addListener { lvl -> }  // react to changes (ambient service)
 */
object ThermalGuard {
    @Volatile var level: ThermalPolicy.Level = ThermalPolicy.Level.NORMAL; private set
    @Volatile var lastHeadroom: Float = Float.NaN; private set

    private var pm: PowerManager? = null
    private var timer: Timer? = null
    @Volatile private var status = 0
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(ThermalPolicy.Level) -> Unit>()
    private val statusListener = PowerManager.OnThermalStatusChangedListener { s -> status = s; update() }

    @Synchronized fun start(context: Context) {
        if (timer != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return  // headroom needs API 30
        val p = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        pm = p
        try { status = p.currentThermalStatus; p.addThermalStatusListener(statusListener) } catch (_: Exception) {}
        timer = fixedRateTimer("thermal-guard", daemon = true, initialDelay = 0L, period = 10_000L) { update() }
    }

    @Synchronized fun stop() {
        timer?.cancel(); timer = null
        try { pm?.removeThermalStatusListener(statusListener) } catch (_: Exception) {}
    }

    fun addListener(l: (ThermalPolicy.Level) -> Unit) { listeners += l }
    fun removeListener(l: (ThermalPolicy.Level) -> Unit) { listeners -= l }

    private fun update() {
        val h = try { pm?.getThermalHeadroom(10) ?: Float.NaN } catch (_: Exception) { Float.NaN }
        lastHeadroom = h
        val next = ThermalPolicy.levelFor(h, status, level)
        if (next != level) {
            level = next
            android.util.Log.i("UltraThermal", "level=$next headroom=$h status=$status")
            listeners.forEach { try { it(next) } catch (_: Exception) {} }
        }
    }
}
