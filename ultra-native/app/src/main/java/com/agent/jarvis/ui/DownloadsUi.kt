package com.agent.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.agent.jarvis.local.DownloadPlan
import com.agent.jarvis.local.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** [Downloads.assets] while shown (file checks only): every second during a run, every 5 s
 *  otherwise. Null before the first read. */
@Composable
private fun liveAssets(running: Boolean): List<DownloadPlan.Asset>? {
    val context = LocalContext.current
    var assets by remember { mutableStateOf<List<DownloadPlan.Asset>?>(null) }
    LaunchedEffect(running) {
        while (true) {
            assets = withContext(Dispatchers.IO) { Downloads.assets(context) }
            delay(if (running) 1_000 else 5_000)
        }
    }
    return assets
}

/** A phone asleep in Doze cuts a background app's network: keep the screen on while a run goes
 *  and this is on screen (as Settings does for the model download). Keyed on the whole run state,
 *  so it is set again after Settings' own model-download effect clears the same flag mid-run. */
@Composable
private fun KeepScreenOnDuring(run: Downloads.RunState) {
    val view = LocalView.current
    DisposableEffect(run) {
        if (run.running) view.keepScreenOn = true
        onDispose { if (run.running) view.keepScreenOn = false }
    }
}

/** "Download all" that asks once more on mobile data (the brains are GBs). */
@Composable
private fun DownloadAllButton(label: String, running: Boolean, missingBytes: Long) {
    val context = LocalContext.current
    var confirmData by remember { mutableStateOf(false) }
    Button(
        onClick = {
            if (!confirmData && Downloads.onMobileData(context)) confirmData = true
            else { confirmData = false; Downloads.downloadAllMissing(context) }
        },
        enabled = !running,
    ) {
        Text(when {
            running -> "Downloading…"
            confirmData -> "On mobile data: tap again to use ${DownloadPlan.size(missingBytes)}"
            else -> label
        })
    }
}

/** Settings → DOWNLOADS: every model, its size and status, and "Download all missing". */
@Composable
fun DownloadsSection() {
    val context = LocalContext.current
    val run by Downloads.state.collectAsState()
    KeepScreenOnDuring(run)
    val assets = liveAssets(run.running) ?: return
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in assets) {
            val state = run.items[a.id]
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${a.label} · ${DownloadPlan.size(a.bytes)}", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f))
                Text(
                    DownloadPlan.status(a, state),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        a.present -> MaterialTheme.colorScheme.primary
                        state is DownloadPlan.ItemState.Failed -> MaterialTheme.colorScheme.error
                        else -> dim
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        val missing = DownloadPlan.missing(assets)
        if (missing.isEmpty()) {
            Text("Everything is downloaded.", style = MaterialTheme.typography.bodySmall, color = dim)
        } else {
            Text("Missing: ${missing.size} item(s), ${DownloadPlan.size(DownloadPlan.missingBytes(assets))} to download. " +
                "Use Wi-Fi and keep this screen open; leaving it doesn't stop the download.",
                style = MaterialTheme.typography.bodySmall, color = dim)
            DownloadAllButton("Download all missing", run.running, DownloadPlan.missingBytes(assets))
        }
    }
}

/** The chat's first-run card: shown while something essential is missing, until dismissed. */
@Composable
fun DownloadsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(UltraPrefs.downloadsCardDismissed(context)) }
    if (dismissed) return
    val run by Downloads.state.collectAsState()
    val assets = liveAssets(run.running) ?: return
    if (!DownloadPlan.showCard(assets, dismissed = false)) return
    KeepScreenOnDuring(run)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(DownloadPlan.cardText(assets), style = MaterialTheme.typography.bodyMedium)
            val current = assets.firstOrNull { run.items[it.id] is DownloadPlan.ItemState.Running }
            val failed = run.items.count { it.value is DownloadPlan.ItemState.Failed }
            Text(
                when {
                    current != null -> "${current.label}: ${DownloadPlan.status(current, run.items[current.id])}"
                    failed > 0 && !run.running -> "$failed item(s) failed — see Settings → DOWNLOADS"
                    else -> "Speech, voice, memory and the brain run on this phone. Use Wi-Fi."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadAllButton("Download all", run.running, DownloadPlan.missingBytes(assets))
                TextButton(onClick = { UltraPrefs.setDownloadsCardDismissed(context, true); dismissed = true }) {
                    Text("Not now")
                }
            }
        }
    }
}
