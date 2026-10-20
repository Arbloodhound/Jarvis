package com.agent.jarvis.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings ambient listening back after a reboot or an app update — only if the user had it on.
 * Android 15+ refuses a microphone foreground service started from BOOT_COMPLETED, and an update
 * stops the service, so there it posts [ResumeNotice] instead: one tap opens the app, which
 * starts it from the foreground (MainActivity / Jarvis Tests).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AmbientConfig(context).enabled || AmbientListenService.instance != null) return
        if (!com.agent.jarvis.JarvisPower.isOn(context)) return
        if (action == Intent.ACTION_BOOT_COMPLETED && android.os.Build.VERSION.SDK_INT < 35) {
            AmbientListenService.start(context)
        } else {
            ResumeNotice.show(context, action)
        }
    }
}
