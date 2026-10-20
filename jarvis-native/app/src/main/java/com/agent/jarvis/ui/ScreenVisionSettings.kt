package com.agent.jarvis.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.jarvis.local.VisionModel
import com.agent.jarvis.local.VisionNative
import kotlinx.coroutines.delay

/**
 * Settings → SCREEN VISION: the text reader (ML Kit, inside the app) and the vision model
 * (Qwen2.5-VL 3B, a 2.8 GB download) that lets Jarvis look at the screen as a picture.
 */
@Composable
fun ScreenVisionSettings(ctx: Context) {
    var tick by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val downloading = VisionModel.downloading
    // Progress is read off the files on disk, so it carries on after leaving and coming back.
    LaunchedEffect(Unit) { while (true) { delay(1000); if (VisionModel.downloading) tick++ } }
    val present = remember(tick) { VisionModel.present(ctx) }
    val have = remember(tick) { VisionModel.downloadedBytes(ctx) }
    val gb = { b: Long -> String.format(java.util.Locale.ROOT, "%.1f GB", b / 1_073_741_824.0) }

    JarvisHint(
        "Reading the screen as a picture, for what apps don't expose as text: games, maps, images, " +
            "pages drawn on a canvas. The text reader (Google ML Kit) is built in and works offline. " +
            "The vision model looks at the whole picture and can describe it or answer a question " +
            "about it (\"what's in this photo\", \"which button is the blue one\"). It runs on this " +
            "phone; nothing is uploaded and no screenshot is saved. Banking and password screens come " +
            "out black, by Android's design.",
    )
    JarvisHint(
        "If screen reading says the screen could not be captured: turn Jarvis's accessibility " +
            "service off and on once (Android only grants the new screenshot permission on restart).",
    )
    Text("Text reader: built in ✓", style = MaterialTheme.typography.bodyMedium)
    Text("Vision model: Qwen2.5-VL 3B (${gb(VisionModel.TOTAL_BYTES)})", style = MaterialTheme.typography.bodyMedium)
    Text(
        when {
            present && !VisionNative.available -> "Downloaded, but this build's engine can't run it"
            present -> "Downloaded — loads when Jarvis looks, frees itself after 2 minutes"
            downloading -> "Downloading… ${gb(have)} of ${gb(VisionModel.TOTAL_BYTES)}"
            have > 0 -> "Unfinished download: ${gb(have)} so far"
            else -> "Not downloaded"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (downloading) {
        LinearProgressIndicator(progress = { (have.toFloat() / VisionModel.TOTAL_BYTES).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth())
    }
    if (failed) Text("Download failed — press it again to continue where it stopped.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!present && !downloading) {
            Button(onClick = {
                failed = false
                VisionModel.startFetch(ctx) { ok -> failed = !ok; tick++ }
                tick++
            }) { Text(if (have > 0) "Continue download" else "Download vision model") }
        }
        if (present || (have > 0 && !downloading)) {
            OutlinedButton(onClick = { VisionModel.delete(ctx); tick++ }) { Text("Delete") }
        }
    }
}
