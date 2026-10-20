package com.agent.jarvis.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Settings → PEOPLE: the owner's name (both brains are told it, and the memory model writes notes
 * with it instead of "Me"), then the voices heard, to name ([VoiceList]).
 */
@Composable
fun PeopleSettings(context: Context) {
    var name by remember { mutableStateOf(UltraPrefs.ownerName(context)) }
    var saved by remember { mutableStateOf(UltraPrefs.ownerName(context)) }
    JarvisHint(
        "Your name: Jarvis calls you by it, and notes say it instead of \"Me\".",
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Your name") },
            singleLine = true, modifier = Modifier.weight(1f))
        TextButton(enabled = name.trim() != saved, onClick = {
            UltraPrefs.setOwnerName(context, name); saved = UltraPrefs.ownerName(context); name = saved
        }) { Text("Save") }
    }
    VoiceList(context)
}

/**
 * Settings → SYSTEM PROMPT: the owner's own instructions, added to both brains' system prompts
 * ([com.agent.jarvis.agent.LocalPrompt.ownerSection]), and the built-in on-device rules to read.
 */
@Composable
fun SystemPromptSettings(context: Context) {
    var text by remember { mutableStateOf(UltraPrefs.customInstructions(context)) }
    var saved by remember { mutableStateOf(UltraPrefs.customInstructions(context)) }
    var showBuiltIn by remember { mutableStateOf(false) }
    JarvisHint(
        "Your instructions: how Jarvis should talk to you, things it should always know or do. Added to " +
            "both the on-device and the online brain. Example: \"Keep answers under three sentences. I work night " +
            "shifts. Call my wife Anna, not 'your partner'.\"",
        modifier = Modifier.padding(top = 8.dp),
    )
    OutlinedTextField(
        value = text, onValueChange = { text = it.take(UltraPrefs.MAX_INSTRUCTIONS) },
        label = { Text("Your instructions (${text.length}/${UltraPrefs.MAX_INSTRUCTIONS})") },
        minLines = 3, maxLines = 10, modifier = Modifier.fillMaxWidth(),
    )
    Row {
        TextButton(enabled = text.trim() != saved, onClick = {
            UltraPrefs.setCustomInstructions(context, text); saved = UltraPrefs.customInstructions(context)
        }) { Text("Save") }
        TextButton(onClick = { showBuiltIn = !showBuiltIn }) {
            Text(if (showBuiltIn) "Hide the built-in instructions" else "Show the built-in instructions")
        }
    }
    if (showBuiltIn) Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            com.agent.jarvis.agent.LocalPrompt.offlineSystem(UltraPrefs.ownerName(context), saved),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}
