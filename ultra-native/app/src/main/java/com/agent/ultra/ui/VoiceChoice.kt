package com.agent.ultra.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.ultra.agent.TalkStyle
import com.agent.ultra.listen.LocalVoice
import com.agent.ultra.listen.VoiceCatalog

/**
 * Settings → JARVIS'S VOICE: which voice speaks (an own voice from [VoiceCatalog], downloaded once,
 * or the phone's text-to-speech and any of its installed voices), which speaker inside a
 * multi-voice model, speed, pitch, and a preview.
 */
@Composable
fun JarvisVoiceSettings(context: Context) {
    var chosen by remember { mutableStateOf(VoicePrefs.voiceId(context)) }
    var tick by remember { mutableIntStateOf(0) }
    val preview = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { preview.shutdown() } }

    // Progress lines move while something downloads.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            if (VoiceCatalog.all.any { LocalVoice.fetchingNow(it) }) tick++
        }
    }

    fun choose(id: String) {
        chosen = id
        VoicePrefs.setVoiceId(context, id)
        VoiceCatalog.byId(id)?.let { LocalVoice.fetchInBackground(context, it) }
        tick++
    }

    val speaking = remember(chosen, tick) { if (LocalVoice.available) LocalVoice.speakingModel(context) else null }
    Text(
        when {
            chosen == VoiceCatalog.PHONE -> "Speaking with the phone's voice."
            speaking == null -> "Speaking with the phone's voice until a voice of Jarvis's own has downloaded."
            speaking.id != chosen -> "Speaking with ${speaking.label} until ${VoiceCatalog.byId(chosen)?.label} has downloaded."
            else -> "Speaking with ${speaking.label}" +
                VoiceCatalog.speakerName(speaking, VoicePrefs.speaker(context, speaking.id)).let { if (it.isEmpty()) "." else " — $it." }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )

    for (m in VoiceCatalog.all) {
        val present = remember(m.id, tick) { LocalVoice.voicePresent(context, m) }
        val fetching = remember(m.id, tick) { LocalVoice.fetchingNow(m) }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { choose(m.id) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = chosen == m.id, onClick = { choose(m.id) })
            Column(modifier = Modifier.weight(1f)) {
                Text(m.label + if (m.id == VoiceCatalog.DEFAULT) " (recommended)" else "", style = MaterialTheme.typography.bodyMedium)
                Text(
                    m.blurb + " " + when {
                        present -> "Downloaded."
                        fetching -> "Downloading: ${LocalVoice.downloadedBytes(context, m) / 1_048_576} of ${m.sizeMb} MB."
                        else -> "${m.sizeMb} MB download."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (present && chosen != m.id) TextButton(onClick = { LocalVoice.delete(context, m); tick++ }) { Text("Remove") }
        }
        if (chosen == m.id && m.multiSpeaker) SpeakerPicker(context, m)
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { choose(VoiceCatalog.PHONE) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = chosen == VoiceCatalog.PHONE, onClick = { choose(VoiceCatalog.PHONE) })
        Column(modifier = Modifier.weight(1f)) {
            Text("Phone's voice", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Android's text-to-speech (Google's voices on a Pixel). Pick any voice installed on the phone; " +
                    "more can be added in Android Settings → Text-to-speech.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
    if (chosen == VoiceCatalog.PHONE) PhoneVoicePicker(context)

    var speed by remember { mutableStateOf(VoicePrefs.speed(context)) }
    Text("Speaking speed: ${"%.1f".format(speed)}×", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Slider(value = speed, onValueChange = { speed = it }, onValueChangeFinished = { VoicePrefs.setSpeed(context, speed) },
        valueRange = VoicePrefs.MIN_SPEED..VoicePrefs.MAX_SPEED, steps = 9)
    if (chosen == VoiceCatalog.PHONE) {
        var pitch by remember { mutableStateOf(VoicePrefs.pitch(context)) }
        Text("Pitch: ${"%.1f".format(pitch)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value = pitch, onValueChange = { pitch = it }, onValueChangeFinished = { VoicePrefs.setPitch(context, pitch) },
            valueRange = 0.5f..2f, steps = 14)
    }
    OutlinedButton(onClick = {
        preview.speak("Hello, I'm Jarvis. This is how I sound. Shall I carry on?")
    }) { Text("Preview") }
}

/** A multi-voice model's speaker: named ones in a menu, numbered ones with − / +. */
@Composable
private fun SpeakerPicker(context: Context, m: VoiceCatalog.Model) {
    var sid by remember(m.id) { mutableIntStateOf(VoicePrefs.speaker(context, m.id)) }
    fun set(v: Int) { sid = VoiceCatalog.clampSpeaker(m, v); VoicePrefs.setSpeaker(context, m.id, sid) }
    Row(modifier = Modifier.padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (m.speakers.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { open = true }) { Text(VoiceCatalog.speakerName(m, sid)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                m.speakers.forEachIndexed { i, name ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { set(i); open = false })
                }
            }
        } else {
            TextButton(onClick = { set(sid - 10) }) { Text("−10") }
            TextButton(onClick = { set(sid - 1) }) { Text("−") }
            Text("Voice $sid of ${m.maxSpeaker}", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { set(sid + 1) }) { Text("+") }
            TextButton(onClick = { set(sid + 10) }) { Text("+10") }
        }
    }
}

/** The phone engine's installed English voices (offline ones first). */
@Composable
private fun PhoneVoicePicker(context: Context) {
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var current by remember { mutableStateOf(VoicePrefs.phoneVoice(context)) }
    DisposableEffect(Unit) {
        var tts: android.speech.tts.TextToSpeech? = null
        tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                voices = try {
                    tts?.voices.orEmpty().filter { it.locale.language == "en" }
                        .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.locale.toString() }, { it.name }))
                } catch (_: Exception) { emptyList() }
            }
        }
        onDispose { try { tts?.shutdown() } catch (_: Exception) {} }
    }
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.padding(start = 48.dp)) {
        OutlinedButton(onClick = { open = true }) {
            Text(if (current.isEmpty()) "The phone's default voice" else current.take(40))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("The phone's default voice") }, onClick = {
                current = ""; VoicePrefs.setPhoneVoice(context, ""); open = false
            })
            for (v in voices) {
                DropdownMenuItem(
                    text = { Text("${v.name} (${v.locale.displayCountry.ifEmpty { v.locale.toString() }}" +
                        "${if (v.isNetworkConnectionRequired) ", online" else ""})") },
                    onClick = { current = v.name; VoicePrefs.setPhoneVoice(context, v.name); open = false },
                )
            }
        }
    }
}

/** Settings → HOW JARVIS TALKS: personality, answer length, the wake reply and the live-voice switches. */
@Composable
fun HowJarvisTalksSettings(context: Context) {
    var personality by remember { mutableStateOf(VoicePrefs.personality(context)) }
    Text("Personality", style = MaterialTheme.typography.bodyMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in TalkStyle.Personality.values()) FilterChip(selected = personality == p,
            onClick = { personality = p; VoicePrefs.setPersonality(context, p) }, label = { Text(p.label) })
    }
    var length by remember { mutableStateOf(VoicePrefs.answerLength(context)) }
    Text("Answer length", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in TalkStyle.Length.values()) FilterChip(selected = length == l,
            onClick = { length = l; VoicePrefs.setAnswerLength(context, l) }, label = { Text(l.label) })
    }
    var think by remember { mutableStateOf(VoicePrefs.spokenThink(context)) }
    Text("Think time when you speak to it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in TalkStyle.SpokenThink.values()) FilterChip(selected = think == t,
            onClick = { think = t; VoicePrefs.setSpokenThink(context, t) },
            label = { Text("${t.label} (~${t.seconds.toInt()} s)") })
    }
    Hint("Longer thinking gives better answers to hard questions; Instant starts talking soonest.")

    var reply by remember { mutableStateOf(VoicePrefs.wakeReply(context)) }
    OutlinedTextField(
        value = reply,
        onValueChange = { reply = it.take(60); VoicePrefs.setWakeReply(context, reply) },
        label = { Text("Reply to a bare \"Hey Jarvis\" (empty = just the chime)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    TalkSwitch("Wake chime", "A short beep the moment \"Hey Jarvis\" is heard.",
        VoicePrefs.wakeChime(context)) { VoicePrefs.setWakeChime(context, it) }
    TalkSwitch("Say \"On it.\"", "When an answer takes more than a couple of seconds, so you know it heard you.",
        VoicePrefs.sayOnIt(context)) { VoicePrefs.setSayOnIt(context, it) }
    TalkSwitch("Speak as it writes", "Says each sentence as soon as it's written instead of waiting for the whole answer.",
        VoicePrefs.speakAsWritten(context)) { VoicePrefs.setSpeakAsWritten(context, it) }
    TalkSwitch("Plain \"stop\" while it works", "\"Stop\", \"cancel\" or \"never mind\" stops a task without saying \"Hey Jarvis\" first.",
        VoicePrefs.bareStop(context)) { VoicePrefs.setBareStop(context, it) }
    TalkSwitch("\"Hey Jarvis\" cuts in", "Saying \"Hey Jarvis\" while it talks stops it so you can speak.",
        VoicePrefs.bargeIn(context)) { VoicePrefs.setBargeIn(context, it) }
}

@Composable
private fun TalkSwitch(title: String, hint: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember { mutableStateOf(initial) }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Hint(hint)
        }
        Switch(checked = on, onCheckedChange = { on = it; onChange(it) })
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
}
