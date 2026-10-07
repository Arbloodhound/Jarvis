package com.agent.ultra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agent.ultra.listen.ConversationInsights
import com.agent.ultra.listen.Insights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Conversation insights: how the talking went today / this week / this month, from what Jarvis
 * heard ([ConversationInsights]): who talked most, interruptions, who answers whom, fillers, mood,
 * questions, topics, busiest hour, and each person's favourite words. "Save as note" files it.
 */
@Composable
fun InsightsScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(ConversationInsights.Period.TODAY) }
    var report by remember { mutableStateOf<ConversationInsights.Report?>(null) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(period) {
        report = null; saved = false
        report = withContext(Dispatchers.IO) { runCatching { Insights.report(context, period) }.getOrNull() }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                Text("Insights", style = MaterialTheme.typography.titleLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in ConversationInsights.Period.values()) FilterChip(selected = period == p,
                    onClick = { period = p }, label = { Text(p.label.replaceFirstChar { it.uppercase() }) })
            }
            val r = report
            if (r == null) { Text("Counting…"); return@Column }
            Text("${r.lines} lines · ${r.words} words heard", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Card {
                Text("What stood out", style = MaterialTheme.typography.titleMedium)
                for (p in r.patterns) Text("• $p", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            for (s in r.speakers.take(8)) Card {
                val share = s.share(r.words)
                Text(if (s.name == com.agent.ultra.listen.CueTags.ME) "You" else s.name, style = MaterialTheme.typography.titleMedium)
                Text("$share% of the talking · ${s.words} words · ${s.lines} lines", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { share / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                val bits = listOfNotNull(
                    "${s.questions} questions".takeIf { s.questions > 0 },
                    s.topFiller()?.let { "fillers ${s.fillers} (most: \"${it.key}\")" },
                    "talked over ${s.overlapped}×".takeIf { s.overlapped > 0 },
                    "${s.positive} positive / ${s.negative} negative words".takeIf { s.positive + s.negative > 0 },
                    "polite ${s.polite}×".takeIf { s.polite > 0 },
                    "laughed ${s.laughs}×".takeIf { s.laughs > 0 },
                    "swore ${s.swears}×".takeIf { s.swears > 0 },
                    "faster than usual ${s.fast}×".takeIf { s.fast > 0 },
                    "louder than usual ${s.louder}×".takeIf { s.louder > 0 },
                )
                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                s.favourites().takeIf { it.isNotEmpty() }?.let {
                    Text("Favourite words: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !saved && r.words > 0, onClick = {
                    scope.launch(Dispatchers.IO) {
                        com.agent.ultra.listen.MemoryNotes.get(context).addInsight(ConversationInsights.noteText(r, period.label))
                        saved = true
                    }
                }) { Text(if (saved) "Saved to Notes" else "Save as note") }
                OutlinedButton(enabled = r.words > 0, onClick = {
                    shareText(context, ConversationInsights.noteText(r, period.label))
                }) { Text("Share") }
            }
            Text("Counted from what Jarvis heard and who it thinks said it (Settings → VOICE → Who's speaking). " +
                "Speaker labels are guesses; lines heard before labels were on count as Unknown.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

/** Android's share sheet for [text]. */
fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(send, "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
