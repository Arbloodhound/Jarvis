package com.agent.ultra

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat

class UltraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Thermal headroom polling (API 30+; no-op below). Read via ThermalGuard.level.
        try { com.agent.ultra.agent.ThermalGuard.start(this) } catch (_: Exception) {}
        // Pre-tick apps that look like they hold money or credentials, once,
        // before the agent has a chance to read anything.
        try { com.agent.ultra.ui.ProtectedApps.seedIfUnset(this) } catch (_: Exception) {}
        // Foreground service keeps the process alive when backgrounded —
        // on this device class (3.5GB Samsung) backgrounded processes lose
        // their accessibility binding and get reclaimed.
        try {
            ContextCompat.startForegroundService(
                this, Intent(this, AgentBackgroundService::class.java)
            )
        } catch (e: Exception) {
            android.util.Log.w("UltraApp", "background service start failed", e)
        }
    }

    companion object {
        lateinit var instance: UltraApplication
            private set
    }
}
