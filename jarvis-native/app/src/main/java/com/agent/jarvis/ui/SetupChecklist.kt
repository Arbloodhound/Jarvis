package com.agent.jarvis.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One place to finish setting the phone up: every switch Jarvis needs, each with one button, and
 * how many are done. A slim line in the chat while something required is missing; the list opens
 * from it.
 */
object SetupSteps {
    data class Step(val id: String, val label: String, val why: String, val done: Boolean, val required: Boolean = true)

    /** Required steps done, out of required steps. Pure, JVM-tested. */
    fun progress(steps: List<Step>): Pair<Int, Int> =
        steps.count { it.required && it.done } to steps.count { it.required }

    fun complete(steps: List<Step>): Boolean = steps.none { it.required && !it.done }

    val PERMISSIONS: Map<String, List<String>> = buildMap {
        put("mic", listOf(android.Manifest.permission.RECORD_AUDIO))
        put("phone", listOf(android.Manifest.permission.CALL_PHONE, android.Manifest.permission.ANSWER_PHONE_CALLS))
        put("texts", listOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS))
        put("contacts", listOf(android.Manifest.permission.READ_CONTACTS))
        put("location", listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION))
        if (Build.VERSION.SDK_INT >= 33) put("notify", listOf(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun granted(c: Context, perms: List<String>) = perms.all {
        androidx.core.content.ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED
    }

    /** The steps as they stand on this phone now. */
    fun read(c: Context): List<Step> {
        val notifAccess = android.provider.Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")
            .orEmpty().contains(c.packageName)
        val pm = c.getSystemService(android.os.PowerManager::class.java)
        val battery = pm?.isIgnoringBatteryOptimizations(c.packageName) == true
        val models = try {
            com.agent.jarvis.local.Downloads.assets(c).filter { it.essential }.all { it.present }
        } catch (_: Exception) { false }
        fun p(id: String) = granted(c, PERMISSIONS[id].orEmpty())
        return listOfNotNull(
            Step("a11y", "Screen control (accessibility)", "Lets Jarvis see the screen and tap and type in apps.",
                com.agent.jarvis.AgentAccessibilityService.getInstance() != null),
            Step("mic", "Microphone", "Hearing you, and \"Hey Jarvis\".", p("mic")),
            Step("models", "On-device models downloaded", "The brain, the action model, speech and voice. Several GB: use Wi-Fi.", models),
            Step("phone", "Phone calls", "Calling, answering and hanging up.", p("phone")),
            Step("texts", "Text messages", "Reading and sending SMS.", p("texts")),
            Step("contacts", "Contacts", "\"Call mom\" looks the name up here.", p("contacts")),
            Step("notifs", "Reading notifications", "Telling you what came in. Android keeps this on its own screen.", notifAccess),
            Step("battery", "Keep working with the screen off", "Stops Android putting Jarvis to sleep.", battery),
            if (PERMISSIONS.containsKey("notify")) Step("notify", "Showing notifications", "Warnings and what it is doing.", p("notify")) else null,
            Step("location", "Location", "Only for \"where am I\".", p("location"), required = false),
        )
    }
}

/** The line in the chat while setup is unfinished, and the checklist it opens. */
@Composable
fun SetupChecklistBanner(context: Context) {
    var tick by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val steps = remember(tick, open) { SetupSteps.read(context) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }
    if (SetupSteps.complete(steps) && !open) return
    val (done, total) = SetupSteps.progress(steps)

    if (!SetupSteps.complete(steps)) Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { open = true },
    ) {
        Text(
            "Setup: $done of $total done. Tap to finish ›",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }

    if (open) AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
        title = { Text("Finish setting up ($done of $total)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (s in steps) Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.label + if (s.required) "" else " (optional)", style = MaterialTheme.typography.bodyMedium)
                        Text(s.why, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    if (s.done) Text("✓", color = MaterialTheme.colorScheme.primary)
                    else TextButton(onClick = { act(context, s.id) { launcher.launch(it) }; tick++ }) {
                        Text(if (s.id == "models") "Download" else "Allow")
                    }
                }
            }
        },
    )
}

private fun act(c: Context, id: String, request: (Array<String>) -> Unit) {
    fun open(i: Intent) = try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    when (id) {
        "a11y" -> open(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        "notifs" -> open(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        "battery" -> open(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:" + c.packageName)))
        "models" -> {
            val started = com.agent.jarvis.local.Downloads.downloadAllMissing(c)
            android.widget.Toast.makeText(c,
                if (started) "Downloading the models. You can keep using the app." else "A download is already running.",
                android.widget.Toast.LENGTH_LONG).show()
        }
        else -> SetupSteps.PERMISSIONS[id]?.let { request(it.toTypedArray()) }
    }
}
