package com.agent.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agent.jarvis.agent.MemoryFocus
import com.agent.jarvis.listen.MemoryNotes
import com.agent.jarvis.listen.Note
import com.agent.jarvis.listen.NoteKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Every note in one place: the ones the memory model made from what Jarvis heard and the ones
 * the owner made ("note: …"). Filter by kind, search, add, delete, and "Ask Jarvis about my
 * notes" (the chat in Notes focus, [MemoryFocus]).
 */
@Composable
fun NotesScreen(onBack: () -> Unit, onAsk: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { MemoryNotes.get(context) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var kind by remember { mutableStateOf<NoteKind?>(null) }
    var query by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var tag by remember { mutableStateOf<String?>(null) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var organising by remember { mutableStateOf(false) }
    var organiseMsg by remember { mutableStateOf("") }
    LaunchedEffect(kind, query, reload, tag) {
        val (list, n) = withContext(Dispatchers.IO) {
            runCatching { store.list(kind, query, 500) to store.count() }.getOrDefault(emptyList<Note>() to 0)
        }
        notes = if (tag == null) list else list.filter { tag in it.tags }
        total = n
        tags = withContext(Dispatchers.IO) { runCatching { store.allTags() }.getOrDefault(emptyList()) }
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    val zone = remember { ZoneId.systemDefault() }
    val owner = remember { JarvisPrefs.ownerName(context) }
    val heardFmt = remember { SimpleDateFormat("EEE d MMM, h:mm a", Locale.UK) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            JarvisHeader(title = "Notes", onBack = onBack, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text("$total", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(end = 12.dp))
            }
            LazyRow(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val chips = listOf<Pair<String, NoteKind?>>("All" to null, "Summaries" to NoteKind.SUMMARY, "To-dos" to NoteKind.TODO,
                    "Plans" to NoteKind.PLAN, "People" to NoteKind.PERSON, "Facts" to NoteKind.FACT, "Patterns" to NoteKind.OBSERVATION)
                items(chips) { (label, k) ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) })
                }
            }
            if (tags.isNotEmpty()) LazyRow(modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tags) { t ->
                    FilterChip(selected = tag == t, onClick = { tag = if (tag == t) null else t }, label = { Text("#$t") })
                }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search notes") },
                singleLine = true, shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = newNote, onValueChange = { newNote = it }, placeholder = { Text("New note") },
                    maxLines = 3, modifier = Modifier.weight(1f))
                Button(enabled = newNote.isNotBlank(), onClick = {
                    val text = newNote.trim(); newNote = ""
                    scope.launch { withContext(Dispatchers.IO) { runCatching { store.addUser(text) } }; reload++ }
                }) { Text("Add") }
            }
            if (notes.isEmpty()) {
                Text(
                    if (total == 0) "No notes yet. Say \"note: …\" or type one above. With ambient listening on, " +
                        "the memory model also makes notes from what it hears while the phone charges."
                    else "Nothing matches.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp).weight(1f),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(notes, key = { it.id }) { n ->
                        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(listOfNotNull(n.kind.label, n.whenText(zone), n.who?.let { w -> if (w == "Me" && owner.isNotBlank()) owner else w })
                                        .joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        Text(n.text, style = MaterialTheme.typography.bodyMedium)   // hold to select
                                    }
                                    if (n.tags.isNotEmpty()) Text(n.tags.joinToString(" ") { "#$it" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                    Text((if (n.sourceIds.isEmpty()) "you noted it " else "heard ") + heardFmt.format(Date(n.heardTs)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                }
                                Column {
                                    TextButton(onClick = {
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(n.text))
                                        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                                    }) { Text("Copy", style = MaterialTheme.typography.labelSmall) }
                                    TextButton(onClick = { shareText(context, n.text) }) { Text("Share", style = MaterialTheme.typography.labelSmall) }
                                    TextButton(onClick = {
                                        scope.launch { withContext(Dispatchers.IO) { runCatching { store.delete(n.id) } }; reload++ }
                                    }) { Text("✕", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                                }
                            }
                        }
                    }
                }
            }
            if (organiseMsg.isNotBlank()) Text(organiseMsg, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 12.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The memory model sorts new speech into notes now, not only later while charging.
                androidx.compose.material3.OutlinedButton(enabled = !organising, onClick = {
                    organising = true; organiseMsg = "Organising what was heard into notes…"
                    scope.launch {
                        organiseMsg = withContext(Dispatchers.IO) {
                            runCatching { com.agent.jarvis.listen.Librarian.runNow(context) }.getOrElse { "Couldn't organise: ${it.message}" }
                        }
                        organising = false; reload++
                    }
                }, modifier = Modifier.weight(1f)) { Text(if (organising) "Organising…" else "Organise now") }
                // Every note shown, as one text to send or keep elsewhere.
                androidx.compose.material3.OutlinedButton(enabled = notes.isNotEmpty(), onClick = {
                    shareText(context, notes.joinToString("\n\n") { n ->
                        listOfNotNull(n.kind.label, n.whenText(zone), n.who).joinToString(" · ") + "\n" + n.text +
                            (if (n.tags.isEmpty()) "" else "\n" + n.tags.joinToString(" ") { "#$it" })
                    })
                }) { Text("Share all") }
                Button(onClick = {
                    ChatStore.focus.value = MemoryFocus.Focus.NOTES
                    onAsk()
                }, modifier = Modifier.weight(1f)) { Text("Ask about notes") }
            }
        }
    }
}

/** "To-do", "Plan", … for the Notes screen. */
val NoteKind.label: String
    get() = when (this) {
        NoteKind.TODO -> "To-do"; NoteKind.PLAN -> "Plan"; NoteKind.PERSON -> "Person"
        NoteKind.FACT -> "Fact"; NoteKind.OBSERVATION -> "Pattern"; NoteKind.SUMMARY -> "Summary"
    }
