package com.agent.jarvis.listen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.agent.jarvis.MainActivity

/**
 * "Jarvis isn't listening — tap to turn it back on." Ambient listening is a microphone
 * foreground service, and Android 15+ won't let one start from the boot broadcast; an app update
 * also stops it. Opening the app restarts it ([AmbientListenService.resumeIfOff]), so this
 * notification is that tap. Pixel results file 2026-10-06 20:44: "on=yes running=no" after a
 * restart, until the app was opened.
 */
object ResumeNotice {
    private const val CHANNEL = "ambient_resume"
    private const val ID = 7102

    /** Why listening stopped, for the notification text. Pure. */
    fun reason(action: String?): String =
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "Jarvis was updated" else "The phone restarted"

    fun show(ctx: Context, action: String?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Listening paused", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val tap = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(ID, NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle("Jarvis isn't listening")
            .setContentText("${reason(action)}. Tap to turn listening back on.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setAutoCancel(true).setContentIntent(tap)
            .build())
    }

    /** Listening is back: the reminder is no longer true. */
    fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
