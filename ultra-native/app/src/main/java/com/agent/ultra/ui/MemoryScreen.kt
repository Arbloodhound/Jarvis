package com.agent.ultra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agent.ultra.agent.MemoryFocus
import com.agent.ultra.listen.AmbientMemory
import com.agent.ultra.listen.MemoryRecall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything ambient listening kept, newest first and grouped by day, with search by meaning and
 * words ([MemoryRecall], the same search the brain uses), and "Ask Jarvis about my memory" (the
 * chat in Memory focus, [MemoryFocus]).
 */
@Composable
fun MemoryScreen(onBack: () -> Unit, onAsk: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(400)   // search once typing pauses
        loading = true
        lines = withContext(Dispatchers.IO) {
            runCatching {
                if (query.isBlank()) AmbientMemory.get(context).newest(500).map { it.ts to it.text }
                else MemoryRecall.relevant(context, query, System.currentTimeMillis(), 50)
                    .filter { it.source == MemoryRecall.SOURCE_HEARD }.map { it.ts to it.text }
            }.getOrDefault(emptyList())
        }
        loading = false
    }
    androidx.activity.compose.BackHandler(onBack = onBack)
    val dayFmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.UK) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.UK) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                Text("Memory", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = query, onValueChange = { query = it },
                placeholder = { Text("Search what Jarvis heard") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            if (!loading && lines.isEmpty()) {
                Text(
                    if (query.isBlank()) "Nothing heard yet. Turn on ambient listening (Settings → VOICE) and Jarvis " +
                        "keeps what is said near the phone, on the phone only."
                    else "Nothing matches.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp).weight(1f),
                )
            } else {
                // Newest first, a day heading wherever the day changes (search results by time too).
                val sorted = lines.sortedByDescending { it.first }
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    var lastDay = ""
                    val rows = sorted.map { (ts, text) ->
                        val day = dayFmt.format(Date(ts))
                        val head = if (day != lastDay) day else null
                        lastDay = day
                        Triple(head, ts, text)
                    }
                    items(rows) { (head, ts, text) ->
                        Column {
                            head?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                            }
                            Row {
                                Text(timeFmt.format(Date(ts)), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(end = 8.dp))
                                Text(text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Button(onClick = { ChatStore.focus.value = MemoryFocus.Focus.MEMORY; onAsk() },
                modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Ask Jarvis about my memory") }
        }
    }
}
