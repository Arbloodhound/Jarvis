package com.agent.ultra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The complete guide inside the app (owner, 2026-10-07: "add that into the app itself"): every
 * feature, setting, how it works, what it can't do, and "How do I…?" recipes. Read from
 * assets/guide.md; Contents jumps to a section, Search keeps only the sections that match.
 */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val all = remember {
        val md = try { context.assets.open("guide.md").bufferedReader().use { it.readText() } }
            catch (_: Exception) { "# Guide\n\nThe guide is missing from this build." }
        GuideText.parse(md)
    }
    var query by remember { mutableStateOf("") }
    var showContents by remember { mutableStateOf(false) }
    val blocks = remember(query) { GuideText.filter(all, query) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp)) {
            JarvisHeader(title = "Guide", onBack = onBack, backText = "← Back", modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { showContents = !showContents }) { Text(if (showContents) "Close" else "Contents") }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it; showContents = false },
                label = { Text("Search the guide") }, singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (showContents) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(GuideText.sections(blocks)) { _, (index, title) ->
                        Text(title, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable {
                                showContents = false
                                scope.launch { list.scrollToItem(index) }
                            }.padding(vertical = 10.dp))
                    }
                }
            } else {
                if (blocks.isEmpty()) Text("Nothing in the guide matches \"$query\".", style = MaterialTheme.typography.bodyMedium)
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(blocks) { _, b -> GuideBlock(b) }
                }
            }
        }
    }
}

@Composable
private fun GuideBlock(b: GuideText.Block) {
    when (b) {
        is GuideText.Block.Heading -> Text(
            rich(b.text),
            style = when (b.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            color = if (b.level == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = if (b.level == 3) 12.dp else 20.dp, bottom = 4.dp),
        )
        is GuideText.Block.Bullet -> Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
            Text("•  ", style = MaterialTheme.typography.bodyMedium)
            Text(rich(b.text), style = MaterialTheme.typography.bodyMedium)
        }
        is GuideText.Block.Tip -> Text(
            rich("💡 " + b.text), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(vertical = 6.dp),
        )
        is GuideText.Block.Para -> Text(rich(b.text), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp))
    }
}

private fun rich(text: String) = buildAnnotatedString {
    for ((piece, bold) in GuideText.boldRuns(text)) {
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(piece) } else append(piece)
    }
}
