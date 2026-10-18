package com.agent.ultra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.provider.ProviderConfig
import kotlinx.coroutines.launch

/**
 * Choose the brain, from the chat's model chip: the cloud model (when a key is set) or any on-device
 * model already downloaded. Switching flips [UltraPrefs.offlineOnly] (which decides cloud vs local for
 * chat AND voice) and, for a local pick, points the engine at that model ([LocalModelEngine.selectModel]
 * — the same setter Settings uses). [onChanged] bumps the config version so the brain rebuilds.
 *
 * Switching cloud PROVIDER (e.g. Gemini vs Claude) means a different base URL + key, so that stays in
 * Settings → AI PROVIDER; here you pick the cloud model id for the provider you've set up.
 */
@Composable
fun ModelPicker(localEngine: LocalModelEngine, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { ProviderConfig.load(context) }
    var offline by remember { mutableStateOf(UltraPrefs.offlineOnly(context)) }
    var cloudModel by remember { mutableStateOf(cfg.model) }
    val downloaded = remember { localEngine.downloadedFileNames() }
    val localModels = remember { LocalModelEngine.PRESETS.filter { it.fileName in downloaded } }
    val currentLocal = remember { localEngine.modelFileName }
    val cloudIds = remember {
        (listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5") + cfg.model)
            .filter { it.isNotBlank() }.distinct()
    }
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Choose the brain") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("CLOUD", style = MaterialTheme.typography.labelSmall, color = faint)
                if (!cfg.isUsable) {
                    Text("No API key yet — add one in Settings → AI PROVIDER.",
                        style = MaterialTheme.typography.bodySmall, color = faint)
                } else {
                    cloudIds.forEach { id ->
                        Row(selected = !offline && cloudModel == id, label = "Cloud · $id") {
                            ProviderConfig.save(context, cfg.copy(model = id))
                            UltraPrefs.setOfflineOnly(context, false)
                            offline = false; cloudModel = id
                            onChanged()
                        }
                    }
                }
                Text("Base URL + key (e.g. Gemini) live in Settings → AI PROVIDER.",
                    style = MaterialTheme.typography.bodySmall, color = faint,
                    modifier = Modifier.padding(top = 4.dp))
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("ON-DEVICE", style = MaterialTheme.typography.labelSmall, color = faint)
                if (localModels.isEmpty()) {
                    Text("None downloaded — get one in Settings → DOWNLOADS.",
                        style = MaterialTheme.typography.bodySmall, color = faint)
                } else {
                    localModels.forEach { m ->
                        Row(selected = offline && currentLocal == m.fileName, label = "${m.label} (on-device)") {
                            scope.launch {
                                localEngine.selectModel(m)
                                UltraPrefs.setOfflineOnly(context, true)
                                offline = true
                                onChanged()
                                onDismiss()
                            }
                        }
                    }
                }
            }
        },
    )
}

/** One selectable row: a radio + label, the whole row tappable. */
@Composable
private fun Row(selected: Boolean, label: String, onPick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
