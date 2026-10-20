package com.agent.jarvis

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The on/off switch for everything Jarvis runs in the background (owner, 2026-10-20: "an on and off
 * button … so it's not draining all my battery"). Off: any run stops, background listening and the
 * keep-alive service stop, both models leave memory, and the memory model does no background work.
 * Nothing restarts them (app start, boot) until it is turned on again. Opening the app and asking
 * still works; the model loads for that question.
 *
 * Not switchable from an app: the accessibility service (Android only turns it off in its own
 * settings). It does nothing on its own while nothing asks it to.
 *
 * Switched from the Quick Settings tile ([JarvisTileService]) and Settings.
 */
object JarvisPower {
    private const val PREFS = "jarvis_power"
    private const val K_ON = "on"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ON, true)

    fun set(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ON, on).apply()
        android.util.Log.i("JarvisApp", "Jarvis turned ${if (on) "ON" else "OFF"}")
        if (on) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(app, Intent(app, AgentBackgroundService::class.java))
            } catch (e: Exception) { android.util.Log.w("JarvisApp", "background service start failed: ${e.message}") }
            com.agent.jarvis.listen.AmbientListenService.resumeIfOff(app)
        } else {
            com.agent.jarvis.agent.Brain.stopAll()
            com.agent.jarvis.ui.SpeechQueue.stopAll()
            com.agent.jarvis.listen.AmbientListenService.stop(app)
            app.stopService(Intent(app, AgentBackgroundService::class.java))
            scope.launch {
                runCatching { com.agent.jarvis.local.LocalModelEngine.librarian(app).unload() }
                runCatching { com.agent.jarvis.local.LocalModelEngine.shared(app).unload() }
            }
        }
        JarvisTileService.refresh(app)
    }

    fun toggle(ctx: Context) = set(ctx, !isOn(ctx))
}
