package com.agent.ultra.agent

import com.agent.ultra.AgentAccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * In-process device control. Replaces the old RN bridge: Kotlin calls the
 * accessibility service and system APIs directly. All methods are safe to
 * call from any coroutine; blocking service gestures hop to Dispatchers.IO.
 */
class AgentController(private val context: Context) {

    private val service get() = AgentAccessibilityService.getInstance()

    // ── What has been read off the screen that must not travel ──────
    //
    // Held here rather than in the navigator because the dangerous flow crosses
    // tools: a deep read of a banking screen, then a navigation that types into
    // a messaging app. Both go through this object, so this is the one place
    // that sees the whole path.
    //
    // Values only, never persisted, cleared at the start of every request. A
    // list of secrets that outlived the task that saw them would be a worse
    // thing than the leak it prevents.
    private val seenSecrets = mutableListOf<com.agent.ultra.gate.ScreenSecrets.Seen>()

    /** Note anything secret-shaped on a screen, with the app it was seen in. */
    fun noteScreenSecrets(screenText: String) {
        if (screenText.isBlank()) return
        val pkg = activePackage()
        if (pkg.isBlank()) return
        val found = com.agent.ultra.gate.ScreenSecrets.find(screenText, pkg)
        for (f in found) {
            if (seenSecrets.none { it.value == f.value && it.pkg == f.pkg }) {
                seenSecrets += f
                android.util.Log.i("UltraFlow", "noted ${f.why} on screen in ${f.pkg}")
            }
        }
        // A handful is all a single request should ever produce; a screen
        // generating hundreds is noise, not evidence.
        while (seenSecrets.size > 32) seenSecrets.removeAt(0)
    }

    /** Why this text must not be typed into the app in front, or null. */
    fun refuseTyping(text: String): String? =
        com.agent.ultra.gate.ScreenSecrets.refuseTyping(text, activePackage(), seenSecrets)

    /**
     * A tracked secret contained in text that is about to leave the phone.
     *
     * Typing has a legitimate destination — the app the value came from.
     * Sending does not: a message goes outward and never comes home, so every
     * value counts here regardless of where it was read.
     */
    fun outboundSecret(text: String): String? {
        if (text.isBlank()) return null
        return seenSecrets.firstOrNull { text.contains(it.value) }
            ?.let { "${it.why} read from ${it.pkg}" }
    }

    /** Forget everything at the start of a new request. */
    fun forgetScreenSecrets() { seenSecrets.clear() }

    val serviceRunning: Boolean get() = AgentAccessibilityService.isRunning()

    /**
     * Why the service is not running, in words the user can act on.
     *
     * "Accessibility service not running" is true and unhelpful when Android's
     * own switch is already showing ON — which happens after a reinstall, where
     * the setting survives but the binding does not.
     */
    val serviceProblem: String get() = when {
        AgentAccessibilityService.isRunning() -> ""
        AgentAccessibilityService.listedButNotBound(context) ->
            "the accessibility service is switched on in Android's settings but is not " +
                "actually running — this happens after an update. Turn it OFF and back ON " +
                "under Settings > Accessibility > Installed apps > Agent Ultra."
        else ->
            "the accessibility service is off. Tap the red text at the top of the chat " +
                "screen to switch it on; nothing can be read or tapped until then."
    }

    /**
     * The service can be momentarily unbound (bind races after process start,
     * Samsung background churn). Poll briefly before declaring it absent.
     */
    private suspend fun serviceOrWait(): AgentAccessibilityService? = withContext(Dispatchers.IO) {
        var svc = service
        var tries = 0
        while (svc == null && tries < 6) {
            tries++
            try { Thread.sleep(500) } catch (_: InterruptedException) {}
            svc = service
        }
        svc
    }

    // ── Perception ──────────────────────────────────────────────────────

    /**
     * Flat a11y node list. The service can briefly return empty/null while an
     * app is settling after a transition (proven on device: root=null bursts
     * after Chrome launched) — retry with backoff before reporting empty.
     */
    suspend fun screenFlat(): String = withContext(Dispatchers.IO) {
        val svc = serviceOrWait() ?: return@withContext "[]"
        var result = svc.getScreenContentFlat() ?: "[]"
        var tries = 0
        while ((result == "[]" || result.isBlank()) && tries < 4) {
            tries++
            try { Thread.sleep(700) } catch (_: InterruptedException) {}
            result = svc.getScreenContentFlat() ?: "[]"
        }
        result
    }

    /** Foreground app from live windows (falls back to the event tracker). */
    fun activePackage(): String = service?.getForegroundPackage() ?: service?.getActivePackage() ?: ""

    suspend fun waitForUiChange(timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        service?.waitForUiChange(timeoutMs) ?: false
    }

    // ── Actions (accessibility) ─────────────────────────────────────────

    /** Click the node at [index] in the last flat dump, refusing if what is
     * there now is not what was seen. See the service for why. */
    suspend fun clickByViewId(vid: String, fallbackIndex: Int, expectedLabel: String): String =
        withContext(Dispatchers.IO) {
            service?.clickByViewId(vid, fallbackIndex, expectedLabel) ?: "failed"
        }

    suspend fun clickByIndex(index: Int, expectedLabel: String): String =
        withContext(Dispatchers.IO) {
            service?.clickByIndex(index, expectedLabel) ?: "failed"
        }

    // The agent never answers its own question. While the action gate is waiting for the person,
    // its "Do it" button is on screen, and a touch by coordinates is the one kind of touch that
    // could land on it (taps by node can't: they only see the app's own windows).
    suspend fun longClickByIndex(index: Int, expectedLabel: String): String =
        withContext(Dispatchers.IO) {
            if (ActionGate.asking) "failed" else service?.longClickByIndex(index, expectedLabel) ?: "failed"
        }

    suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        if (ActionGate.asking) false else service?.performTap(x, y) ?: false
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (ActionGate.asking) false else service?.performSwipe(x1, y1, x2, y2, durationMs) ?: false
        }

    suspend fun clickNode(selector: String): Boolean = withContext(Dispatchers.IO) {
        service?.performClick(selector) ?: false
    }

    suspend fun typeInto(selector: String, text: String): Boolean = withContext(Dispatchers.IO) {
        service?.performText(selector, text) ?: false
    }

    suspend fun scroll(direction: String): Boolean = withContext(Dispatchers.IO) {
        service?.performScroll(direction) ?: false
    }

    /** The full structural tree, containers included — for grouping labels
     * into the items they belong to. Separate from screenFlat(), whose
     * indices the navigator taps by. */
    suspend fun screenTree(): String = withContext(Dispatchers.IO) {
        serviceOrWait()?.getScreenTree() ?: "[]"
    }

    /** Scroll the largest scrollable container in the target app's window —
     * for reading a whole page rather than nudging whatever is nearest. */
    suspend fun scrollDeep(direction: String): Boolean = withContext(Dispatchers.IO) {
        serviceOrWait()?.performScrollDeep(direction) ?: false
    }

    suspend fun imeEnter(): Boolean = withContext(Dispatchers.IO) {
        service?.performImeAction() ?: false
    }

    suspend fun back(): Boolean = withContext(Dispatchers.IO) { service?.performBack() ?: false }
    suspend fun home(): Boolean = withContext(Dispatchers.IO) { service?.performHome() ?: false }

    // ── Apps ────────────────────────────────────────────────────────────

    fun launchApp(packageName: String): Boolean {
        // Launching goes through an Intent, not the accessibility service, so
        // the app policy has to be asked here too. Otherwise "only apps I
        // choose" would still put a bank on screen — unreadable, but open.
        if (!AgentAccessibilityService.agentMayUse(packageName)) {
            android.util.Log.i("UltraNav", "launch refused by app policy: $packageName")
            return false
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun findPackage(query: String): String? {
        val q = query.lowercase()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        // Ranking lives in AppMatch so it can be tested off the phone.
        return AppMatch.find(q, apps.map { AppMatch.App(it.loadLabel(pm).toString(), it.activityInfo.packageName) })
    }

    fun listLaunchableApps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map {
            it.loadLabel(pm).toString() to it.activityInfo.packageName
        }.sortedBy { it.first }
    }

    fun openUrl(url: String): Boolean = try {
        val u = if (url.startsWith("http")) url else "https://$url"
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) { false }

    // ── Radios & settings (WRITE_SECURE_SETTINGS granted via adb) ──────

    /** Direct settings write — the fix for the old QS-tile tap failures. */
    fun setSecureSetting(key: String, value: String): Boolean = try {
        Settings.Secure.putString(context.contentResolver, key, value)
    } catch (_: Exception) { false }

    fun setGlobalSetting(key: String, value: String): Boolean = try {
        Settings.Global.putString(context.contentResolver, key, value)
    } catch (_: Exception) { false }

    fun setBluetooth(on: Boolean): Boolean = try {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return false
        @Suppress("DEPRECATION")
        if (on) adapter.enable() else adapter.disable()
        true
    } catch (_: Exception) { false }

    fun setWifi(on: Boolean): Boolean = try {
        // WifiManager.setWifiEnabled is deprecated but functional via shell-granted perms;
        // panel intent is the user-facing fallback.
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.isWifiEnabled = on
        true
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    fun setDoNotDisturb(on: Boolean): Boolean {
        // Path 1: notification policy access (user-granted in settings)
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(
                    if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                )
                return true
            }
        } catch (_: Exception) {}
        // Path 2: zen_mode global setting (works with WRITE_SECURE_SETTINGS)
        return try {
            Settings.Global.putInt(context.contentResolver, "zen_mode", if (on) 1 else 0)
        } catch (_: Exception) { false }
    }

    fun setFlashlight(on: Boolean): Boolean = try {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        cm.setTorchMode(id, on)
        true
    } catch (_: Exception) { false }

    fun setVolume(stream: Int, percent: Int): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream)
        am.setStreamVolume(stream, (max * percent / 100), 0)
        true
    } catch (_: Exception) { false }

    // ── Out-of-band state queries (verification, not action) ────────────

    fun isWifiEnabled(): Boolean? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.isWifiEnabled
    } catch (_: Exception) { null }

    fun isBluetoothEnabled(): Boolean? = try {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        adapter?.isEnabled
    } catch (_: Exception) { null }

    fun isDoNotDisturbOn(): Boolean? = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
    } catch (_: Exception) { null }

    fun volumePercent(stream: Int = AudioManager.STREAM_MUSIC): Int? = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(stream)
        if (max > 0) (am.getStreamVolume(stream) * 100) / max else null
    } catch (_: Exception) { null }

    // ── Communication ───────────────────────────────────────────────────

    fun sendSms(to: String, message: String): Boolean = try {
        @Suppress("DEPRECATION")
        SmsManager.getDefault().sendTextMessage(to, null, message, null, null)
        true
    } catch (_: Exception) { false }

    fun readSms(limit: Int): String = try {
        val cursor = context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"), null, null, "date DESC"
        ) ?: return "Error: could not query SMS inbox"
        val out = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext() && out.size < limit) {
                val addr = it.getString(0) ?: "unknown"
                val body = (it.getString(1) ?: "").take(160)
                out += "From $addr: $body"
            }
        }
        if (out.isEmpty()) "No SMS messages found" else out.joinToString("\n")
    } catch (e: SecurityException) {
        "Error: SMS permission not granted"
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    fun readContacts(nameQuery: String): String = try {
        val selection: String?
        val args: Array<String>?
        if (nameQuery.isBlank()) {
            selection = null; args = null
        } else {
            selection = android.provider.ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?"
            args = arrayOf("%$nameQuery%")
        }
        val cursor = context.contentResolver.query(
            android.provider.ContactsContract.Contacts.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER),
            selection, args,
            android.provider.ContactsContract.Contacts.DISPLAY_NAME + " ASC"
        ) ?: return "Error: could not query contacts"
        val out = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext() && out.size < 10) {
                val id = it.getString(0)
                val name = it.getString(1) ?: continue
                val hasPhone = it.getInt(2) > 0
                var phone = ""
                if (hasPhone) {
                    val pc = context.contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(id), null)
                    pc?.use { p -> if (p.moveToFirst()) phone = p.getString(0) ?: "" }
                }
                out += if (phone.isNotBlank()) "$name — $phone" else name
            }
        }
        if (out.isEmpty()) "No contacts matching '$nameQuery'" else out.joinToString("\n")
    } catch (e: SecurityException) {
        "Error: contacts permission not granted"
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    // ── Location / clipboard / media / alarms / notes ───────────────────

    fun lastKnownLocation(): String = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER)
        var best: android.location.Location? = null
        for (p in providers) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best!!.time)) best = l
            } catch (_: SecurityException) {}
        }
        if (best == null) "Error: no known location yet"
        else "Location: %.5f, %.5f (±%.0fm)".format(best.latitude, best.longitude, best.accuracy)
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    fun clipboardWrite(text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ultra", text))
        true
    } catch (_: Exception) { false }

    fun clipboardRead(): String = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) "Clipboard is empty"
        else clip.getItemAt(0).coerceToText(context)?.toString() ?: "Clipboard is empty"
    } catch (_: Exception) { "Error: clipboard read failed" }

    fun mediaKey(keyCode: Int): Boolean = try {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
        true
    } catch (_: Exception) { false }

    fun setAlarm(hour: Int, minute: Int, label: String): Boolean = try {
        val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
            .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
        context.startActivity(i)
        true
    } catch (e: Exception) {
        android.util.Log.w("AgentUltra", "setAlarm failed", e)
        false
    }

    fun createNote(text: String): String = try {
        val dir = java.io.File(context.filesDir, "notes").apply { mkdirs() }
        val f = java.io.File(dir, "note-${System.currentTimeMillis()}.txt")
        f.writeText(text)
        "Note saved: ${f.name}"
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    suspend fun takeScreenshot(): Boolean = withContext(Dispatchers.IO) {
        serviceOrWait()?.takeScreenshot() ?: false
    }

    /** Recent notifications captured by the listener service. */
    fun readNotifications(limit: Int): String = try {
        val f = java.io.File(context.filesDir, "notifications.log")
        if (!f.exists()) "No notifications captured yet (or the listener isn't enabled)"
        else {
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) "No notifications captured yet"
            else lines.takeLast(limit).joinToString("\n") {
                it.substringAfter(" | ") // drop the epoch prefix
            }
        }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }

    // ── Device info ─────────────────────────────────────────────────────

    fun batteryStatus(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "Battery: $level%${if (charging) " (charging)" else ""}"
    }

    fun deviceInfo(): String = buildString {
        append("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        append(" | apps: ").append(listLaunchableApps().size)
    }

    /**
     * The current state of the settings people ask about, read — never changed.
     *
     * Measured (learnrun pass A, 2026-09-19): asked "is bluetooth on right now", the brain
     * had nothing to read the answer from and called bluetooth_toggle {"on":true}. A
     * question must have a read-only answer, or the model will answer it with an action.
     */
    fun settingsStatus(): String {
        fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "unknown" }
        val night = try {
            val ui = context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
            when (ui.nightMode) {
                android.app.UiModeManager.MODE_NIGHT_YES -> "on"
                android.app.UiModeManager.MODE_NIGHT_NO -> "off"
                android.app.UiModeManager.MODE_NIGHT_AUTO -> "auto"
                else -> if ((context.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES) "on" else "off"
            }
        } catch (_: Exception) { "unknown" }
        val location = try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            onOff(if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else null)
        } catch (_: Exception) { "unknown" }
        val storage = try {
            val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            "%.1f GB free of %.1f GB".format(st.availableBytes / 1e9, st.totalBytes / 1e9)
        } catch (_: Exception) { "unknown" }
        return "Wi-Fi ${onOff(isWifiEnabled())}${currentWifiSsid()?.let { " ($it)" } ?: ""} | " +
            "Bluetooth ${onOff(isBluetoothEnabled())} | Do not disturb ${onOff(isDoNotDisturbOn())} | " +
            "Dark mode $night | Location $location | Volume ${volumePercent() ?: "?"}% | Storage $storage"
    }

    /** Settings pages Android opens directly — no tapping through menus. */
    val SETTINGS_PAGES = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "about" to Settings.ACTION_DEVICE_INFO_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "notifications" to "android.settings.NOTIFICATION_SETTINGS",
        "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "security" to Settings.ACTION_SECURITY_SETTINGS,
        "network" to Settings.ACTION_WIRELESS_SETTINGS,
        "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "main" to Settings.ACTION_SETTINGS,
    )

    fun openSettings(page: String): Boolean {
        if (!AgentAccessibilityService.agentMayUse("com.android.settings")) return false
        val action = SETTINGS_PAGES[page.lowercase().trim()] ?: return false
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: Exception) { false }
    }

    /** The app in front of the user right now, as the accessibility service sees it. */
    fun foregroundPackage(): String? =
        AgentAccessibilityService.getInstance()?.getCurrentPackage()?.takeIf { it.isNotBlank() }

    fun currentWifiSsid(): String? = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wm.connectionInfo?.ssid?.removeSurrounding("\"")
    } catch (_: Exception) { null }
}