package com.agent.ultra.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.ultra.listen.AmbientConfig
import com.agent.ultra.listen.AmbientMemory
import com.agent.ultra.listen.Voice
import com.agent.ultra.listen.VoicePrints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → ambient listening → who's speaking: speaker tags on/off, "This is my voice"
 * (owner enrolment), and the voices heard so far, to name ("that was Sam") or forget.
 * Voiceprints are biometric data and never leave the phone.
 */
/**
 * Live "Hey Jarvis" status, refreshed every second: is listening running, is the "Hey Jarvis" sound
 * model loaded, when it last heard speech and what, when it last woke and how, the last command.
 * Shows where a missed "Hey Jarvis" stopped (owner, 2026-10-07: "it doesn't respond").
 */
@Composable
fun HeyJarvisStatus() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val S = com.agent.ultra.listen.AmbientListenService
    fun ago(t: Long) = if (t <= 0) "not yet" else "${(now - t) / 1000} s ago"
    val running = S.instance?.capturing == true
    Text(
        buildString {
            append("Hey Jarvis status: ")
            append(if (running) "listening" else "NOT listening (open Jarvis to start it)")
            if (running) append(if (S.gateLoaded) " · sound model on" else " · sound model off (words only)")
            append("\nLast speech: ").append(ago(S.lastSpeechAt))
            if (S.lastHeardText.isNotBlank()) append(" · heard \"").append(S.lastHeardText).append('"')
            append("\nLast wake: ").append(ago(S.lastWakeAt))
            if (S.lastWakeAt > 0) append(" (by ").append(S.lastWakeBy).append(')')
            append("\nLast command: ").append(ago(S.lastCommandAt))
            if (S.lastCommandAt > 0) append(" · \"").append(com.agent.ultra.agent.IntentRouter.requestPart(S.lastCommand).take(60)).append('"')
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (running) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f) else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
fun VoiceSettings(context: Context) {
    HeyJarvisStatus()
    val cfg = remember { AmbientConfig(context) }
    val mem = remember { AmbientMemory.get(context) }
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(cfg.speakerTags) }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            runCatching { mem.voices() to mem.voiceLineCounts() }.getOrNull()
        }?.let { voices = it.first; counts = it.second }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Who's speaking", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Marks each heard line with who said it ([Me], a name you give, or [Speaker 2]), plus " +
                    "pauses, talking faster or louder than usual, and laughter. Uses voiceprints made and " +
                    "kept only on this phone (a 40 MB model, downloaded on Wi-Fi once this is on). It's a guess: similar " +
                    "voices, TV and distance can mix people up or split one person in two. Check the law " +
                    "where you live before recording other people.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Switch(checked = on, onCheckedChange = { on = it; cfg.speakerTags = it })
    }

    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (!granted) status = "Microphone permission is needed to record your voice." }

    OutlinedButton(
        enabled = !busy,
        onClick = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                return@OutlinedButton
            }
            busy = true
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    VoicePrints.enrol(context) { s ->
                        scope.launch {
                            status = when (s) {
                                "downloading" -> "Downloading the voice model (about 40 MB)…"
                                "recording" -> "Recording: talk normally for 10 seconds…"
                                else -> "Saving…"
                            }
                        }
                    }
                }
                // recording your voice is asking for speaker tags
                if (r == VoicePrints.Enrol.Done && !on) { on = true; cfg.speakerTags = true }
                status = when (r) {
                    VoicePrints.Enrol.Done -> "Got it. Lines in your voice are marked [Me]."
                    is VoicePrints.Enrol.Failed -> r.why
                }
                busy = false
                refresh++
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text(if (voices.any { it.owner }) "Record my voice again (10 s)" else "This is my voice: record 10 s") }
    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }

    if (voices.isNotEmpty()) Text(
        "Name the voices it has heard in Settings → PEOPLE.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Settings → PEOPLE: the voices heard so far, to name ("that was Sam") or forget. Names show in
 * transcripts and notes ("[Sam]") and the brain reads them. Voiceprints never leave the phone.
 */
@Composable
fun VoiceList(context: Context) {
    val mem = remember { AmbientMemory.get(context) }
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            runCatching { mem.voices() to mem.voiceLineCounts() }.getOrNull()
        }?.let { voices = it.first; counts = it.second }
    }
    if (voices.isEmpty()) {
        Text(
            "No voices yet. Turn on \"Who's speaking\" (Settings → VOICE) and the people it hears appear " +
                "here as Speaker 2, Speaker 3 … to name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }
    run {
        Text(
            "Voices heard. Type a name to label a voice (give two voices the same name to join them).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    for (v in voices) VoiceRow(v, counts[v.id] ?: 0,
        onRename = { name ->
            scope.launch { withContext(Dispatchers.IO) { runCatching { mem.renameVoice(v.id, name) } }; refresh++ }
        },
        onDelete = {
            scope.launch { withContext(Dispatchers.IO) { runCatching { mem.deleteVoice(v.id) } }; refresh++ }
        },
    )
    if (voices.isNotEmpty()) {
        OutlinedButton(
            onClick = {
                scope.launch { withContext(Dispatchers.IO) { runCatching { mem.wipeVoices() } }; refresh++ }
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Forget all voices") }
    }
}

@Composable
private fun VoiceRow(v: Voice, lines: Int, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember(v.id, v.name) { mutableStateOf(v.name ?: "") }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            (if (v.owner) "Me (your voice)" else v.label) + " · $lines lines",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!v.owner) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRename(name) }) { Text("Save") }
            }
            TextButton(onClick = onDelete) { Text(if (v.owner) "Forget my voice" else "Forget") }
        }
    }
}
