package com.agent.ultra.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restart ambient listening after reboot — only if the user had it on.
 * Android 15+ refuses a microphone foreground service started from BOOT_COMPLETED,
 * so there it waits for the app to be opened (MainActivity starts it then).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED &&
            AmbientConfig(context).enabled &&
            android.os.Build.VERSION.SDK_INT < 35
        ) {
            AmbientListenService.start(context)
        }
    }
}
