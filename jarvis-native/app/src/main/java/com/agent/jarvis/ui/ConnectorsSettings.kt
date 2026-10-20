package com.agent.jarvis.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.agent.jarvis.harness.connectors.AppConnectors
import com.agent.jarvis.harness.connectors.GoogleAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → CONNECTORS: one Google sign-in for Gmail, Calendar, Drive and Docs (Google's Android
 * authorization screen), and what each connector's status is. Disconnect removes the stored tokens.
 */
@Composable
fun ConnectorsSettings(context: Context) {
    val scope = rememberCoroutineScope()
    var line by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf(false) }

    fun refreshStatus() {
        connected = AppConnectors.google(context).all { it.isConnected() }
    }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { refreshStatus() } }

    fun keep(token: String?) {
        if (token.isNullOrBlank()) { line = "Google didn't give a token. Nothing was connected."; return }
        GoogleAccess.store(AppConnectors.vault(context), token, AppConnectors.google(context))
        refreshStatus()
        line = "Connected: Gmail, Calendar, Drive and Docs."
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        line = try { keep(GoogleAccess.resultFrom(context, res.data).accessToken); line }
        catch (e: Exception) { "Not connected: ${e.message ?: e.javaClass.simpleName}" }
    }

    JarvisHint(
        "One Google sign-in for Gmail, Calendar, Drive and Docs. Reads run when you ask; anything that " +
            "sends or writes asks you first. Needs an Android OAuth client for this app in your Google " +
            "Cloud project (package com.agent.jarvis and this build's signing key).",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            line = "Asking Google…"
            scope.launch {
                try {
                    val r = GoogleAccess.authorize(context, AppConnectors.googleScopes(context))
                    val pi = r.pendingIntent
                    if (r.hasResolution() && pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    else keep(r.accessToken)
                } catch (e: Exception) {
                    line = "Not connected: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }) { Text(if (connected) "Reconnect Google" else "Connect Google") }
        if (connected) OutlinedButton(onClick = {
            val v = AppConnectors.vault(context)
            GoogleAccess.CONNECTORS.forEach { id -> v.handleFor(id)?.let(v::remove) }
            refreshStatus()
            line = "Disconnected. The tokens were removed from this phone."
        }) { Text("Disconnect") }
    }
    line?.let { JarvisHint(it) }
    // Spotify: the owner's own Spotify app (developer.spotify.com) client ID; redirect URI to register there:
    // com.agent.jarvis://spotify-callback. PKCE, so no secret.
    var spotifyId by remember { mutableStateOf(com.agent.jarvis.harness.connectors.SpotifyAuth.clientId(context)) }
    var spotifyLine by remember { mutableStateOf<String?>(null) }
    val spotifyOn = remember(spotifyLine) { AppConnectors.vault(context).handleFor("spotify") != null }
    androidx.compose.material3.OutlinedTextField(
        value = spotifyId, onValueChange = { spotifyId = it.trim() },
        label = { Text("Spotify client ID (redirect: com.agent.jarvis://spotify-callback)") },
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(), singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = spotifyId.isNotBlank(), onClick = {
            com.agent.jarvis.harness.connectors.SpotifyAuth.setClientId(context, spotifyId)
            spotifyLine = if (com.agent.jarvis.harness.connectors.SpotifyAuth.start(context)) "Finish signing in in the browser." else "Set the client ID first."
        }) { Text(if (spotifyOn) "Reconnect Spotify" else "Connect Spotify") }
        if (spotifyOn) OutlinedButton(onClick = {
            com.agent.jarvis.harness.connectors.SpotifyAuth.disconnect(context)
            spotifyLine = "Spotify disconnected."
        }) { Text("Disconnect") }
    }
    spotifyLine?.let { JarvisHint(it) }

    var trusted by remember { mutableStateOf(AppConnectors.trustedRecipients(context).joinToString("\n")) }
    var trustedSaved by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.OutlinedTextField(
        value = trusted, onValueChange = { trusted = it; trustedSaved = null },
        label = { Text("Email these without asking each time (one address per line)") },
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(), minLines = 2,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            scope.launch {
                AppConnectors.setTrustedRecipients(context, trusted.lines())
                trusted = AppConnectors.trustedRecipients(context).joinToString("\n")
                trustedSaved = "Saved. Every other address — and anything that costs money — still asks."
            }
        }) { Text("Save") }
        trustedSaved?.let { JarvisHint(it) }
    }
}
