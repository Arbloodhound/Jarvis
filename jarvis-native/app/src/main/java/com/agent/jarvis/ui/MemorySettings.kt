package com.agent.jarvis.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.jarvis.listen.AmbientConfig
import com.agent.jarvis.listen.MemoryNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Organise memory while charging": the librarian's switch, its note count, and a way to
 *  forget the notes. Shown under ambient listening in Settings. */
@Composable
fun MemorySettings(context: Context) {
    val cfg = remember { AmbientConfig(context) }
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(cfg.organiseMemory) }
    var count by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        count = withContext(Dispatchers.IO) { runCatching { MemoryNotes.get(context).count() }.getOrDefault(-1) }
    }
    JarvisSwitchRow(
        title = "Organise memory into notes",
        detail = "With the screen off and Jarvis idle (on the charger, or above 50% battery), a small model on the phone " +
            "turns what it heard into notes: a summary of each conversation, plans, to-dos, people and facts, " +
            "with who said it and topic tags. \"Organise now\" on the Notes screen does it straight away. " +
            if (count >= 0) "$count notes so far." else "",
        checked = on,
        onCheckedChange = { on = it; cfg.organiseMemory = it },
    )
    var rememberChat by remember { mutableStateOf(cfg.rememberChat) }
    JarvisSwitchRow(
        title = "Remember what I tell Jarvis",
        detail = "What you type or say to Jarvis is kept in memory too, so it becomes notes and can be found later.",
        checked = rememberChat, enabled = true,
        onCheckedChange = { rememberChat = it; cfg.rememberChat = it },
    )
    var patterns by remember { mutableStateOf(cfg.notePatterns) }
    var readings by remember { mutableStateOf(cfg.patternReadings) }
    JarvisSwitchRow(
        title = "Note conversation patterns",
        detail = "Also notes how people talk: promises, hedging, topic changes, interruptions, " +
            "agreement, pauses. Each note quotes the exact words and time. Observations only, never judgements.",
        checked = patterns, enabled = on,
        onCheckedChange = { patterns = it; cfg.notePatterns = it },
    )
    JarvisSwitchRow(
        title = "Add possible psychological readings",
        detail = "Adds a hedged possible reading to some observations (e.g. \"may suggest deflection\"), " +
            "with a confidence and another explanation. A guess from a small model, not a fact or a diagnosis.",
        checked = readings, enabled = on && patterns, indent = 32.dp,
        onCheckedChange = { readings = it; cfg.patternReadings = it },
    )
    OutlinedButton(
        onClick = {
            scope.launch {
                count = withContext(Dispatchers.IO) {
                    runCatching {
                        val notes = MemoryNotes.get(context)
                        notes.wipe()
                        notes.count()
                    }.getOrDefault(-1)
                }
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text("Erase notes") }
}
