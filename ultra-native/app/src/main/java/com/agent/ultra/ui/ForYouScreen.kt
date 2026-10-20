package com.agent.ultra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.agent.ultra.agent.ForYou
import kotlinx.coroutines.launch

/** "For you": the feed written to the owner's prompt, and ideas from their notes (HANDOFF_V5 §5). */
@Composable
fun ForYouScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf(ForYou.unavailable(context)) }
    val feed = remember(tick) { ForYou.feed(context) }
    val ideas = remember(tick) { ForYou.ideas(context) }
    var prompt by remember { mutableStateOf(feed.prompt()) }
    var auto by remember { mutableStateOf(ForYou.auto(context)) }
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    fun run(block: suspend () -> String) {
        busy = true; line = "Working…"
        scope.launch { line = try { block() } catch (e: Exception) { "Failed: ${e.message}" }; busy = false; tick++ }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("For you", style = MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Refresh by itself", style = MaterialTheme.typography.bodyMedium)
                Text("Hourly feed, new ideas every few hours, and a notification for the best one — within quiet " +
                    "hours and at most 3 a day. Off by default; uses the cloud brain.", style = MaterialTheme.typography.bodySmall, color = faint)
            }
            Switch(checked = auto, onCheckedChange = { auto = it; ForYou.setAuto(context, it) })
        }
        line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = faint) }

        Text("FEED", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("What your feed is about") },
            modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && prompt.isNotBlank(), onClick = { feed.setPrompt(prompt); tick++ }) { Text("Save prompt") }
            OutlinedButton(enabled = !busy, onClick = { run { ForYou.generateFeed(context) } }) { Text("Refresh feed") }
        }
        feed.units().forEach { u ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(u.title, style = MaterialTheme.typography.titleSmall)
                    Text(ChatMarkdown.render(u.body), style = MaterialTheme.typography.bodyMedium)
                    Text(u.category, style = MaterialTheme.typography.labelSmall, color = faint)
                    Row {
                        TextButton(onClick = { feed.engage(u.id, "like"); tick++ }) { Text("More like this") }
                        TextButton(onClick = { feed.engage(u.id, "dismiss"); tick++ }) { Text("Not for me") }
                        TextButton(onClick = { feed.removeByUser(u.id); tick++ }) { Text("Remove") }
                    }
                }
            }
        }

        Text("IDEAS (from your notes)", style = MaterialTheme.typography.titleSmall)
        Text(com.agent.ultra.harness.Ideas.RANK_RULE, style = MaterialTheme.typography.bodySmall, color = faint)
        OutlinedButton(enabled = !busy, onClick = { run { ForYou.generateIdeas(context) } }) { Text("Find ideas") }
        // Bandit-ranked: ideas on topics the owner acts on rise; dismissed ones fall. Shown = a
        // decision point for each surfaced idea's arm.
        var ideaList by remember { mutableStateOf<List<com.agent.ultra.harness.Ideas.Idea>>(emptyList()) }
        LaunchedEffect(tick) {
            ideaList = ForYou.rankedIdeas(context)
            ideaList.forEach { ForYou.onIdeaShown(context, it) }
        }
        ideaList.forEach { i ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(i.title, style = MaterialTheme.typography.titleSmall)
                    Text(i.why, style = MaterialTheme.typography.bodyMedium)
                    Text("From ${i.sources.joinToString()}", style = MaterialTheme.typography.labelSmall, color = faint)
                    Row {
                        TextButton(onClick = { ideas.act(i.id); scope.launch { ForYou.onIdeaActed(context, i) }; tick++ }) { Text("Doing it") }
                        TextButton(onClick = { ideas.dismiss(i.id, "not now"); scope.launch { ForYou.onIdeaDismissed(context, i) }; tick++ }) { Text("Not now") }
                    }
                }
            }
        }
    }
}
