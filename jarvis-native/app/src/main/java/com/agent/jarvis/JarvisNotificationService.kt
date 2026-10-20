package com.agent.jarvis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.Toast
import com.agent.jarvis.agent.EventTrigger
import java.io.File

/**
 * Notification listener: keeps a rolling buffer of recent notifications in
 * app-private storage so the brain's notification_read tool can answer
 * "what did I miss" without leaving the device.
 *
 * Also runs deterministic event triggers on each notification.
 *
 * Enabled via: settings put secure enabled_notification_listeners
 */
class JarvisNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
        // The app policy used to be loaded only when the accessibility service
        // connected; with that service off, every protected app's notifications
        // were processed and logged.
        AgentAccessibilityService.loadBlockedPackages(this)
        EventTrigger.registerDefaults()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            // The same rule every read and action asks (blocklist, or allowlist mode).
            if (!AgentAccessibilityService.agentMayUse(sbn.packageName)) {
                Log.i(TAG, "skipped protected app: ${sbn.packageName}")
                return
            }
            val extras = sbn.notification.extras
            val title = extras.getCharSequence("android.title")?.toString() ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            val warned = runTriggers(sbn.packageName, title, text, sbn.key,
                (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0)

            // Capture is standing collection, not a per-task read: without
            // this check every banking alert, message preview and two-factor
            // code lands in a log on disk.
            if (!com.agent.jarvis.ui.JarvisPrefs.captureNotifications(this)) return
            // Notification filter (Settings, off by default): quiet hours and a per-app burst limit
            // decide what Jarvis keeps. The triggers above already saw it (scam shield, codes).
            if (com.agent.jarvis.ui.JarvisPrefs.notificationFilter(this) &&
                !forwardingPolicy().shouldForward(com.agent.jarvis.harness.IncomingNotification(sbn.packageName, title, text, sbn.postTime))) {
                Log.i(TAG, "filtered (quiet hours or burst): ${sbn.packageName}")
                return
            }
            val line = buildString {
                append(sbn.postTime)
                append(" | ").append(sbn.packageName)
                if (warned != null) append(" | [LOOKS LIKE A SCAM: ").append(warned).append("]")
                if (title.isNotBlank()) append(" | ").append(title.take(60))
                if (text.isNotBlank()) append(" | ").append(text.take(120))
                append("\n")
            }
            val f = File(filesDir, "notifications.log")
            f.appendText(line)
            if (f.length() > 64 * 1024) {
                val lines = f.readLines()
                f.writeText(lines.takeLast(200).joinToString("\n") + "\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "post capture failed", e)
        }
    }

    private var policy: com.agent.jarvis.harness.NotificationForwardingPolicy? = null
    private var policyHours: Pair<Int, Int>? = null

    /** One policy per quiet-hours setting (it keeps the per-app burst counts). */
    private fun forwardingPolicy(): com.agent.jarvis.harness.NotificationForwardingPolicy {
        val hours = com.agent.jarvis.ui.JarvisPrefs.quietHours(this)
        if (policy == null || policyHours != hours) {
            policy = com.agent.jarvis.harness.NotificationForwardingPolicy(
                ownPackage = packageName, quietStartHour = hours.first, quietEndHour = hours.second)
            policyHours = hours
        }
        return policy!!
    }

    /** Runs the triggers; returns the scam reasons when the scam shield fired. */
    private fun runTriggers(pkg: String, title: String, text: String, key: String?, groupSummary: Boolean): String? {
        // key + summary flag: an updated or re-posted notification doesn't warn or copy a code twice.
        val actions = EventTrigger.evaluate(this, pkg, title, text, key, groupSummary)
        var warned: String? = null
        for (a in actions) {
            when (a.type) {
                EventTrigger.Action.Type.WARN -> {
                    warned = a.data
                    Log.i(TAG, "SCAM WARN from=${pkg}: ${a.data}")
                    // A notification stays until it is read; a toast is gone in seconds and may
                    // not show at all over a full-screen app (seen on the A15 over YouTube).
                    val posted = postWarning(title, a.label)
                    android.os.Handler(mainLooper).post {
                        if (!posted) Toast.makeText(this, "Jarvis: " + a.label, Toast.LENGTH_LONG).show()
                        // Said out loud too: the person most likely to fall for it may not read a toast.
                        if (com.agent.jarvis.ui.JarvisPrefs.speakAnswers(this)) {
                            speaker().speak("Careful. " + a.label + " Don't send money, codes or cards to it.")
                        }
                    }
                }
                EventTrigger.Action.Type.CLIPBOARD_COPY -> {
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("jarvis", a.data))
                        android.os.Handler(mainLooper).post {
                            Toast.makeText(this, a.label, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "clipboard action failed: ${e.message}")
                    }
                }
                EventTrigger.Action.Type.TOAST -> {
                    android.os.Handler(mainLooper).post {
                        Toast.makeText(this, a.label, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        return warned
    }

    /** High-importance "looks like a scam" notification. False when Android won't show it
     *  (POST_NOTIFICATIONS not granted), so the caller falls back to a toast. */
    private fun postWarning(from: String, label: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return false
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(
                    SCAM_CHANNEL, "Scam warnings", android.app.NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "A message that looks like a scam just arrived." })
            }
            val sender = from.ifBlank { "a message" }.take(40)
            val n = androidx.core.app.NotificationCompat.Builder(this, SCAM_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Careful — this looks like a scam")
                .setContentText("From $sender")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                    "From $sender. $label Don't send money, codes or gift cards, and don't open its link. " +
                        "If it says it's family or your bank, call them on a number you already have."))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .build()
            nm.notify(SCAM_ID_BASE + (System.currentTimeMillis() % 1000).toInt(), n)
            true
        } catch (e: Exception) {
            Log.w(TAG, "scam warning notification failed: ${e.message}")
            false
        }
    }

    private var speakerRef: com.agent.jarvis.ui.Speaker? = null
    private fun speaker() = speakerRef ?: com.agent.jarvis.ui.Speaker(this).also { speakerRef = it }

    override fun onDestroy() {
        try { speakerRef?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisNotif"
        private const val SCAM_CHANNEL = "jarvis_scam_warnings"
        // 7001 is AgentBackgroundService's foreground notification and 7101
        // AmbientListenService's; 7000 + (ms % 1000) replaced them.
        private const val SCAM_ID_BASE = 20000
    }
}
