package com.agent.jarvis.agent

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
    // Created in start(), after the API check: the listener type only exists from API 29, and
    // building it in this object's initializer crashed app start on API 26-28.
    private var statusListener: Any? = null

    @Synchronized fun start(context: Context) {
        if (timer != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return  // headroom needs API 30
        val p = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        pm = p
        try {
            status = p.currentThermalStatus
            // A status change reuses the last headroom reading: polling again within ~1 s of the
            // timer returns NaN, which read as NORMAL and made the level flap.
            val l = PowerManager.OnThermalStatusChangedListener { st -> status = st; update(pollHeadroom = false) }
            statusListener = l
            p.addThermalStatusListener(l)
        } catch (_: Exception) {}
        timer = fixedRateTimer("thermal-guard", daemon = true, initialDelay = 0L, period = 10_000L) { update(pollHeadroom = true) }
    }

    @Synchronized fun stop() {
        timer?.cancel(); timer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (statusListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                try { pm?.removeThermalStatusListener(it) } catch (_: Exception) {}
            }
        }
        statusListener = null
    }

    fun addListener(l: (ThermalPolicy.Level) -> Unit) { listeners += l }
    fun removeListener(l: (ThermalPolicy.Level) -> Unit) { listeners -= l }

    // Synchronized: the timer thread and the status callback both land here.
    @Synchronized private fun update(pollHeadroom: Boolean) {
        val h = if (!pollHeadroom) lastHeadroom
            else try { pm?.getThermalHeadroom(10) ?: Float.NaN } catch (_: Exception) { Float.NaN }
        lastHeadroom = h
        val next = ThermalPolicy.levelFor(h, status, level)
        if (next != level) {
            level = next
            android.util.Log.i("JarvisThermal", "level=$next headroom=$h status=$status")
            listeners.forEach { try { it(next) } catch (_: Exception) {} }
        }
    }
}
