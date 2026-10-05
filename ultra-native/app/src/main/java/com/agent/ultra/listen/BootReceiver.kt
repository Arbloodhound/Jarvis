package com.agent.ultra.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart ambient listening after reboot — only if the user had it on.
 * On Android 15+ a microphone FGS started from BOOT_COMPLETED may be blocked
 * until the user opens the app once; the service degrades to "waiting" rather
 * than crashing if so.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            AmbientConfig(context).enabled
        ) {
            try { AmbientListenService.start(context) } catch (_: Exception) {}
        }
    }
}
