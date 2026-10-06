package com.agent.ultra.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.provider.ProviderConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    localEngine: LocalModelEngine,
    onConfigSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Without this the system back button leaves the app entirely instead of
    // returning to the conversation.
    BackHandler { onBack() }

    var cfg by remember { mutableStateOf(ProviderConfig.load(context)) }
    // Saveable: a rotation mid-edit must not throw away what was typed.
    var baseUrl by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(cfg.baseUrl) }
    // Not saveable: saved instance state can be written to disk by the system; a secret stays in memory.
    var apiKey by remember { mutableStateOf(cfg.apiKey) }
    var model by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(cfg.model) }
    var savedFlash by remember { mutableStateOf(false) }

    // A download belongs to the engine and outlives this screen; on return the
    // screen picks it up from there instead of forgetting it.
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val engineProgress by localEngine.downloadProgress.collectAsState()
    LaunchedEffect(engineProgress) {
        val p = engineProgress
        if (p != null) { downloading = true; progress = p } else downloading = false
    }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // The memory model (librarian): its own engine, so its own download state, picked up the same way.
    val librarian = remember { LocalModelEngine.librarian(context) }
    var libDownloading by remember { mutableStateOf(false) }
    var libProgress by remember { mutableFloatStateOf(0f) }
    val libEngineProgress by librarian.downloadProgress.collectAsState()
    LaunchedEffect(libEngineProgress) {
        val p = libEngineProgress
        if (p != null) { libDownloading = true; libProgress = p } else libDownloading = false
    }
    var libDownloadError by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        // ── Cloud provider ────────────────────────────────────────────
        SectionTitle("AI PROVIDER (CLOUD)")
        Text(
            "Drives the tool loop when the network is up. Any OpenAI-compatible endpoint works.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("Base URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("Model") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = {
                cfg = ProviderConfig(baseUrl.trim(), apiKey.trim(), model.trim())
                ProviderConfig.save(context, cfg)
                onConfigSaved()
                savedFlash = true
            }) { Text("Save") }
            Text(
                when {
                    savedFlash -> "Saved."
                    cfg.isUsable -> "Configured."
                    else -> "No API key — offline mode only."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        // ── On-device model ───────────────────────────────────────────
        SectionTitle("ON-DEVICE MODEL")
        Text(
            "A small model that runs entirely on this phone — the offline brain. " +
                "It answers simple device commands without touching the network, takes over " +
                "when the cloud is unreachable, and runs anything you prefix with /local.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        // Status refreshes while the screen is open — loading happens lazily on
        // first use, so a fixed snapshot would say "not loaded" forever.
        var statusTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) { statusTick++; kotlinx.coroutines.delay(1500) }
        }
        val onDisk = remember(statusTick, downloading) { localEngine.downloadedFileNames() }
        // Unfinished downloads (file -> bytes). Same folder for both models, so one listing.
        val partials = remember(statusTick, downloading, libDownloading) { localEngine.partialDownloads() }
        fun gb(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0)
        val liveStatus = remember(statusTick, downloading) {
            when {
                localEngine.loaded ->
                    "In memory and ready (${localEngine.modelFileSizeBytes / 1_048_576} MB)"
                localEngine.modelPresent ->
                    "Downloaded (${localEngine.modelFileSizeBytes / 1_048_576} MB) — loads on first use"
                else -> "Not downloaded"
            }
        }
        Text(localEngine.modelLabel, style = MaterialTheme.typography.bodyMedium)
        Text(
            liveStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        // A download of a model that is neither model's choice now (e.g. the 4B a 16 GB phone
        // started before its default became the 8B): kept, so it can continue, but shown.
        val stranded = partials.filterKeys { it != localEngine.modelFileName && it != librarian.modelFileName }
        for ((f, b) in stranded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Unfinished download on this phone: " +
                        (LocalModelEngine.PRESETS.firstOrNull { it.fileName == f }?.label ?: f) + " (" + gb(b) +
                        "). Pick it under Change model to continue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { localEngine.deletePartial(f); statusTick++ }) { Text("Delete") }
            }
        }
        Text(
            "\"Loads on first use\" is normal. The weights stay on disk until something " +
                "needs them, then take a few seconds to map into memory and stay there " +
                "until the app closes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        // A phone asleep in Doze cuts a background app's network: keep the screen on while the
        // model downloads (only while this screen shows it). Either model's download counts.
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(downloading || libDownloading) {
            view.keepScreenOn = downloading || libDownloading
            onDispose { view.keepScreenOn = false }
        }
        if (downloading) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("Downloading… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        downloadError?.let {
            Text("Download failed: $it", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!localEngine.modelPresent && !downloading) {
                Button(onClick = {
                    downloading = true; downloadError = null; progress = 0f
                    scope.launch {
                        localEngine.downloadModel { p ->
                            // Hop to the main thread: Compose snapshot state is
                            // not thread-safe and this fires from OkHttp's IO
                            // thread throughout the download.
                            scope.launch(kotlinx.coroutines.Dispatchers.Main) { progress = p }
                        }.onFailure { downloadError = it.message }
                        downloading = false
                    }
                }) {
                    val partial = remember(downloading) { localEngine.partialBytes() }
                    Text(if (partial > 0) "Continue download (" +
                        String.format(java.util.Locale.ROOT, "%.1f", partial / 1_073_741_824.0) + " GB done)"
                        else "Download")
                }
            }
            if (localEngine.loaded) {
                OutlinedButton(onClick = { scope.launch { localEngine.unload() } }) { Text("Free memory") }
            }
        }

        run {
            var preload by remember { mutableStateOf(UltraPrefs.preloadBrain(context)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Get the brain ready in advance", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Loads the on-device model in the background when Jarvis opens or starts " +
                            "listening, so your first question doesn't wait for it. Uses the phone's " +
                            "memory while loaded; Android can reclaim it if another app needs it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = preload, onCheckedChange = { on ->
                    preload = on
                    UltraPrefs.setPreloadBrain(context, on)
                    if (on) localEngine.preloadInBackground("setting turned on")
                })
            }
        }

        run {
            var resident by remember { mutableStateOf(UltraPrefs.keepBrainInMemory(context)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep the whole brain in memory", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Copies the model into Jarvis's own memory so Android can't quietly drop " +
                            "parts of it (which makes answers very slow). Takes effect on the next " +
                            "load: tap Free memory, then ask something. Falls back automatically " +
                            "when the phone is short on memory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = resident, onCheckedChange = { on ->
                    resident = on
                    UltraPrefs.setKeepBrainInMemory(context, on)
                })
            }
        }

        // Model chooser
        var showModels by remember { mutableStateOf(false) }
        var customUrl by remember { mutableStateOf("") }
        TextButton(onClick = { showModels = !showModels }) {
            Text(if (showModels) "Hide models" else "Change model")
        }
        if (showModels) {
            val totalRam = remember { LocalModelEngine.totalRamBytes(context) }
            Text(
                "This phone has ${totalRam / (1024 * 1024)} MB of memory. Each model below is " +
                    "marked for how it sits here. Downloading one does not delete the one you " +
                    "have — switching back is instant, and only one is ever in memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            for (m in LocalModelEngine.PRESETS) {
                val here = m.fileName in onDisk
                val current = m.fileName == localEngine.modelFileName
                val fit = LocalModelEngine.fitFor(m, totalRam)
                val fitLabel = when (fit) {
                    com.agent.ultra.local.Fit.COMFORTABLE -> "fits comfortably"
                    com.agent.ultra.local.Fit.TIGHT -> "tight — will run, may slow under load"
                    com.agent.ultra.local.Fit.TOO_BIG -> "too big for this phone"
                }
                val fitColor = when (fit) {
                    com.agent.ultra.local.Fit.COMFORTABLE -> MaterialTheme.colorScheme.primary
                    com.agent.ultra.local.Fit.TIGHT -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    com.agent.ultra.local.Fit.TOO_BIG -> MaterialTheme.colorScheme.error
                }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        m.label + if (current) "  ← in use" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${m.approxMb} MB  ·  $fitLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = fitColor,
                    )
                    val partial = if (here) null else partials[m.fileName]
                    Text(
                        "${when {
                            here -> "on this phone"
                            partial != null -> "unfinished download: ${gb(partial)} so far"
                            else -> "not downloaded"
                        }}\n${m.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!current) {
                            OutlinedButton(
                                enabled = !downloading && fit != com.agent.ultra.local.Fit.TOO_BIG,
                                onClick = { scope.launch {
                                localEngine.selectModel(m)
                                if (!localEngine.modelPresent) {
                                    downloading = true; downloadError = null; progress = 0f
                                    scope.launch {
                                        localEngine.downloadModel { p ->
                                            scope.launch(kotlinx.coroutines.Dispatchers.Main) { progress = p }
                                        }.onFailure { downloadError = it.message }
                                        downloading = false
                                    }
                                }
                                statusTick++
                            } }) { Text(when {
                                here -> "Use this"
                                partial != null -> "Continue & use"
                                else -> "Download & use"
                            }) }
                        }
                        // Not the memory model's file: it may be in use there.
                        if (here && !current && m.fileName != librarian.modelFileName) {
                            TextButton(onClick = {
                                java.io.File(context.filesDir, "models/" + m.fileName).delete()
                                statusTick++
                            }) { Text("Delete file") }
                        }
                        // Not while it downloads, nor the memory model's own unfinished download.
                        if (partial != null && !current && m.fileName != librarian.modelFileName) {
                            TextButton(onClick = {
                                localEngine.deletePartial(m.fileName)
                                statusTick++
                            }) { Text("Delete unfinished download") }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = customUrl,
                onValueChange = { customUrl = it },
                label = { Text("Or paste a GGUF download URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = customUrl.isNotBlank() && !downloading,
                onClick = {
                    val url = customUrl.trim()
                    val name = url.substringAfterLast('/').substringBefore('?')
                        .ifBlank { "custom-model.gguf" }
                    downloading = true; downloadError = null; progress = 0f
                    scope.launch {
                        localEngine.selectModel(
                            com.agent.ultra.local.ModelChoice(
                                label = name.removeSuffix(".gguf"),
                                url = url,
                                fileName = name,
                                approxMb = 0,
                                note = "Custom",
                            )
                        )
                        localEngine.downloadModel { p ->
                            scope.launch(kotlinx.coroutines.Dispatchers.Main) { progress = p }
                        }.onFailure { downloadError = it.message }
                        downloading = false
                        customUrl = ""
                    }
                },
            ) { Text("Download custom model") }
            Text(
                "It must be a GGUF file this build's llama.cpp can read, and it has to fit " +
                    "in memory alongside everything else. Anything past about 2 GB will " +
                    "refuse to load on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        // ── Memory model (librarian) ──────────────────────────────────
        SectionTitle("MEMORY MODEL")
        Text(
            "A small model for sorting and searching what Jarvis remembers. It runs on this " +
                "phone next to the model above, and steps aside if the two don't fit in memory " +
                "together. Not used by anything yet: the memory sorting that will use it comes next, " +
                "so there is no need to download it now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        val libOnDisk = remember(statusTick, libDownloading) { librarian.downloadedFileNames() }
        val libStatus = remember(statusTick, libDownloading) {
            when {
                librarian.loaded ->
                    "In memory and ready (${librarian.modelFileSizeBytes / 1_048_576} MB)"
                librarian.modelPresent ->
                    "Downloaded (${librarian.modelFileSizeBytes / 1_048_576} MB) — loads when needed"
                else -> "Not downloaded"
            }
        }
        Text(librarian.modelLabel, style = MaterialTheme.typography.bodyMedium)
        Text(
            libStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (libDownloading) {
            LinearProgressIndicator(progress = { libProgress }, modifier = Modifier.fillMaxWidth())
            Text("Downloading… ${(libProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        libDownloadError?.let {
            Text("Download failed: $it", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!librarian.modelPresent && !libDownloading) {
                Button(onClick = {
                    libDownloading = true; libDownloadError = null; libProgress = 0f
                    scope.launch {
                        librarian.downloadModel { p ->
                            scope.launch(kotlinx.coroutines.Dispatchers.Main) { libProgress = p }
                        }.onFailure { libDownloadError = it.message }
                        libDownloading = false
                    }
                }) {
                    val partial = remember(libDownloading) { librarian.partialBytes() }
                    Text(if (partial > 0) "Continue download (" +
                        String.format(java.util.Locale.ROOT, "%.1f", partial / 1_073_741_824.0) + " GB done)"
                        else "Download")
                }
            }
            if (librarian.loaded) {
                OutlinedButton(onClick = { scope.launch { librarian.unload() } }) { Text("Free memory") }
            }
        }

        // Memory model chooser: only the small presets.
        var showLibModels by remember { mutableStateOf(false) }
        TextButton(onClick = { showLibModels = !showLibModels }) {
            Text(if (showLibModels) "Hide memory models" else "Change memory model")
        }
        if (showLibModels) {
            for (m in LocalModelEngine.LIBRARIAN_PRESETS) {
                val here = m.fileName in libOnDisk
                val current = m.fileName == librarian.modelFileName
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(
                        m.label + if (current) "  ← in use" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    val partial = if (here) null else partials[m.fileName]
                    Text(
                        "${m.approxMb} MB  ·  ${when {
                            here -> "on this phone"
                            partial != null -> "unfinished download: ${gb(partial)} so far"
                            else -> "not downloaded"
                        }}\n${m.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!current) {
                            OutlinedButton(
                                enabled = !libDownloading,
                                onClick = { scope.launch {
                                    librarian.selectModel(m)
                                    if (!librarian.modelPresent) {
                                        libDownloading = true; libDownloadError = null; libProgress = 0f
                                        scope.launch {
                                            librarian.downloadModel { p ->
                                                scope.launch(kotlinx.coroutines.Dispatchers.Main) { libProgress = p }
                                            }.onFailure { libDownloadError = it.message }
                                            libDownloading = false
                                        }
                                    }
                                    statusTick++
                                } }) { Text(when {
                                    here -> "Use this"
                                    partial != null -> "Continue & use"
                                    else -> "Download & use"
                                }) }
                        }
                        // Not the main model's file: it may be in use there.
                        if (here && !current && m.fileName != localEngine.modelFileName) {
                            TextButton(onClick = {
                                java.io.File(context.filesDir, "models/" + m.fileName).delete()
                                statusTick++
                            }) { Text("Delete file") }
                        }
                        if (partial != null && !current && m.fileName != localEngine.modelFileName) {
                            TextButton(onClick = {
                                librarian.deletePartial(m.fileName)
                                statusTick++
                            }) { Text("Delete unfinished download") }
                        }
                    }
                }
            }
        }

        // ── Recipes ───────────────────────────────────────────────────
        SectionTitle("ROUTINES")
        val recipeDao = remember { com.agent.ultra.data.UltraDatabase.get(context).recipes() }
        var recipeList by remember {
            mutableStateOf<List<com.agent.ultra.data.RecipeEntity>>(emptyList())
        }
        LaunchedEffect(Unit) { recipeList = recipeDao.list() }
        if (recipeList.isEmpty()) {
            Text(
                "None yet. Run a task, then say \"save that as morning briefing\" — " +
                    "after that, \"run my morning briefing\" replays the whole thing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        } else {
            for (r in recipeList) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            steps(r.stepsJson) +
                                if (r.runCount > 0) "  ·  run ${r.runCount}×" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            recipeDao.delete(r.name)
                            recipeList = recipeDao.list()
                        }
                    }) { Text("Delete") }
                }
            }
        }

        // ── App access ────────────────────────────────────────────────
        SectionTitle("WHERE THE AGENT MAY GO")
        var allowMode by remember { mutableStateOf(ProtectedApps.allowlistMode(context)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Only apps I choose", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Recommended. The agent can read and act only in apps you tick below; " +
                        "everything else is invisible to it. The alternative is a block list, " +
                        "which means naming every risky app in advance — on a phone with " +
                        "hundreds of apps that is a bet you lose once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(checked = allowMode, onCheckedChange = {
                allowMode = it
                ProtectedApps.setAllowlistMode(context, it)
            })
        }
        Text(
            "Blocking matters both ways: whatever the agent reads on screen is sent to the " +
                "cloud model to decide what to do next, so \"don't look\" is as important " +
                "as \"don't act\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 6.dp),
        )

        var appQuery by remember { mutableStateOf("") }
        var showApps by remember { mutableStateOf(false) }
        var appList by remember { mutableStateOf<List<ProtectedApps.Entry>>(emptyList()) }
        LaunchedEffect(showApps, allowMode) {
            if (showApps) {
                appList = withContext(kotlinx.coroutines.Dispatchers.IO) { ProtectedApps.installed(context) }
            }
        }
        Text(
            if (!showApps) "Tap below to choose."
            else if (allowMode) "${appList.count { it.allowed }} apps allowed of ${appList.size} installed."
            else "${appList.count { it.protected }} apps protected of ${appList.size} installed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { showApps = !showApps }) {
            Text(if (showApps) "Hide app list" else if (allowMode) "Choose allowed apps" else "Choose protected apps")
        }
        if (showApps) {
            OutlinedTextField(
                value = appQuery,
                onValueChange = { appQuery = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val shown = appList.filter {
                appQuery.isBlank() || it.label.contains(appQuery, true) || it.pkg.contains(appQuery, true)
            }.take(60)
            for (app in shown) {
                val on = if (allowMode) app.allowed else app.protected
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            app.pkg + if (app.suggested) "  ·  looks sensitive" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (app.suggested) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                    Switch(checked = on, onCheckedChange = { checked ->
                        if (allowMode) {
                            ProtectedApps.toggleAllowed(context, app.pkg, checked)
                            appList = appList.map { if (it.pkg == app.pkg) it.copy(allowed = checked) else it }
                        } else {
                            ProtectedApps.toggle(context, app.pkg, checked)
                            appList = appList.map { if (it.pkg == app.pkg) it.copy(protected = checked) else it }
                        }
                    })
                }
            }
            if (shown.size < appList.size) {
                Text(
                    "Showing ${shown.size} of ${appList.size}. Search to narrow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }

        // ── What Android has actually granted ───────────────────────────
        //
        // Every dangerous permission this app declares starts denied, and
        // nothing in the app ever asked for one. Voice, the camera, texts,
        // contacts, the calendar and location therefore did nothing at all on
        // a fresh install, silently, with no way for the owner to find out
        // why. Declaring a permission in the manifest is not being granted it.
        SectionTitle("PERMISSIONS ANDROID CONTROLS")
        PermissionPanel(context)

        SectionTitle("PERSONAL DATA")
        var personal by remember { mutableStateOf(UltraPrefs.allowPersonalData(context)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Messages, contacts and location", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Off by default. These read Android's own databases rather than the " +
                        "screen, so the app list above does not cover them. With this off, " +
                        "the agent cannot read your texts, look up a contact, send an SMS, " +
                        "or fetch your location — it is told so and carries on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(checked = personal, onCheckedChange = {
                personal = it
                UltraPrefs.setAllowPersonalData(context, it)
            })
        }

        var capture by remember { mutableStateOf(UltraPrefs.captureNotifications(context)) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep a notification log", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Off by default. When on, notification titles and previews are written " +
                        "to a file on this phone whether or not you asked for anything — " +
                        "including message previews and one-time codes. Protected apps are " +
                        "never logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(checked = capture, onCheckedChange = {
                capture = it
                UltraPrefs.setCaptureNotifications(context, it)
            })
        }
        OutlinedButton(onClick = {
            scope.launch {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.io.File(context.filesDir, "notifications.log").delete()
                }
            }
        }) { Text("Delete notification log") }

        var smsCode by remember { mutableStateOf(UltraPrefs.autoExtractSmsCode(context)) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Auto-copy verification codes", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "When an SMS with a verification code arrives, copy the code to " +
                        "the clipboard automatically. Needs the notification listener.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(checked = smsCode, onCheckedChange = {
                smsCode = it
                UltraPrefs.setAutoExtractSmsCode(context, it)
            })
        }

        var scamShield by remember { mutableStateOf(UltraPrefs.scamShield(context)) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Scam warnings", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Warn when a message looks like a scam, and ask before sending anything to " +
                        "its numbers or links. Runs on the phone; nothing is saved. Needs the " +
                        "notification listener.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(checked = scamShield, onCheckedChange = {
                scamShield = it
                UltraPrefs.setScamShield(context, it)
            })
        }

        var sysTriggers by remember { mutableStateOf(UltraPrefs.systemTriggers(context)) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("System event triggers", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Toast on battery low/ok, charger plug/unplug, screen on/off, headphones, app install/remove.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(checked = sysTriggers, onCheckedChange = {
                sysTriggers = it
                UltraPrefs.setSystemTriggers(context, it)
            })
        }

        // ── Voice ─────────────────────────────────────────────────────
        SectionTitle("VOICE")
        run {
            var ownSpeech by remember { mutableStateOf(UltraPrefs.ownSpeech(context)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Jarvis's own speech recognition", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Understands what you say with the speech model on this phone: no Google, " +
                            "works offline. Downloads about 60 MB the first time. Off uses Android's " +
                            "built-in recognizer (Google's, on most phones).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = ownSpeech, onCheckedChange = { on ->
                    ownSpeech = on
                    UltraPrefs.setOwnSpeech(context, on)
                })
            }
        }
        run {
            var ownVoice by remember { mutableStateOf(UltraPrefs.ownVoice(context)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Jarvis's own voice", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Speaks answers with the voice model on this phone: no Google, works " +
                            "offline. Downloads about 21 MB the first time, using Android's voice " +
                            "until then. Off uses Android's text-to-speech (Google's, on most phones).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = ownVoice, onCheckedChange = { on ->
                    ownVoice = on
                    UltraPrefs.setOwnVoice(context, on)
                })
            }
        }
        run {
            val ambientCfg = remember { com.agent.ultra.listen.AmbientConfig(context) }
            var ambient by remember { mutableStateOf(ambientCfg.enabled) }
            var wakeName by remember { mutableStateOf(ambientCfg.wakeName) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ambient listening", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Transcribes what it hears, entirely on the phone, and keeps it as searchable " +
                            "memory. It only answers when you say \"$wakeName\". The mic indicator stays on " +
                            "while this runs. Silence and noise are dropped before anything is saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                // The listening service can't run without the mic: ask first, and only
                // show "on" once Android has said yes.
                val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        ambient = true
                        ambientCfg.enabled = true
                        com.agent.ultra.listen.AmbientListenService.start(context)
                    }
                }
                Switch(checked = ambient, onCheckedChange = { on ->
                    if (on && androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.RECORD_AUDIO
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                        return@Switch
                    }
                    ambient = on
                    ambientCfg.enabled = on
                    if (on) com.agent.ultra.listen.AmbientListenService.start(context)
                    else com.agent.ultra.listen.AmbientListenService.stop(context)
                })
            }
            OutlinedTextField(
                value = wakeName,
                onValueChange = { wakeName = it; ambientCfg.wakeName = it },
                label = { Text("Wake name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        com.agent.ultra.listen.AmbientMemory.get(context).wipe()
                        // the notes were made from what was heard: forgetting it forgets them too
                        runCatching { com.agent.ultra.listen.MemoryNotes.get(context).wipe() }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Erase ambient memory") }
            MemorySettings(context)
            Spacer(modifier = Modifier.height(16.dp))
        }

        var speak by remember { mutableStateOf(UltraPrefs.speakAnswers(context)) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Speak answers aloud", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Reads replies out with the phone's own voice. Hands-free sessions always speak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(checked = speak, onCheckedChange = {
                speak = it
                UltraPrefs.setSpeakAnswers(context, it)
            })
        }
        Text(
            "Hands-free: set Jarvis as your digital assistant, then hold the power button " +
                "(or use your phone's assist gesture) to talk without opening the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedButton(onClick = {
            val tries = listOf(
                android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS,
                android.provider.Settings.ACTION_APPLICATION_SETTINGS,
            )
            for (action in tries) {
                try {
                    context.startActivity(
                        android.content.Intent(action)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    break
                } catch (_: Exception) { /* try the next one */ }
            }
        }) { Text("Choose assistant app") }

        run {
            var skipConfirm by remember { mutableStateOf(UltraPrefs.skipConfirmWhenAsked(context)) }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Don't ask when I asked for it", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "If you told Jarvis to send, post or install something, it does it without an " +
                            "\"are you sure?\". It still always asks for anything with money or a price, " +
                            "deleting or resetting, messages to someone not in your contacts, and anything " +
                            "it decided on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Switch(checked = skipConfirm, onCheckedChange = { on ->
                    skipConfirm = on
                    UltraPrefs.setSkipConfirmWhenAsked(context, on)
                })
            }
        }

        // ── Gate audit log ────────────────────────────────────────────
        SectionTitle("GATE AUDIT LOG")
        val auditCount = remember(savedFlash) { com.agent.ultra.gate.GateAuditLog.entryCount(context) }
        val auditSize = remember(savedFlash) { com.agent.ultra.gate.GateAuditLog.fileSizeBytes(context) }
        Text(
            if (auditCount == 0) "No gate decisions recorded yet."
            else "$auditCount decisions (${auditSize / 1024} KB)",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Every gate decision — allowed, blocked, or overridden — is logged here " +
                "with the tool name and observation state. No message content, no args, " +
                "no personal data. Safe to share publicly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = auditCount > 0,
                onClick = {
                    val intent = com.agent.ultra.gate.GateAuditLog.shareIntent(context)
                    context.startActivity(Intent.createChooser(intent, "Share gate audit log"))
                },
            ) { Text("Export & share") }
            if (auditCount > 0) {
                OutlinedButton(onClick = {
                    com.agent.ultra.gate.GateAuditLog.delete(context)
                    savedFlash = !savedFlash
                }) { Text("Delete log") }
            }
        }

        // ── About ─────────────────────────────────────────────────────
        SectionTitle("ABOUT")
        val version = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) { "unknown" }
        }
        // Read from the manifest the gate actually enforces. A literal here
        // went stale twice (24 -> 30 -> 31): adding a tool never reminded
        // anyone to come back and edit this line.
        val toolCount = remember { com.agent.ultra.gate.Manifest.fromAssets(context).size }
        Text(
            "Jarvis $version\n" +
                "Policy gate: " + (if (toolCount > 0) "active (gatellml manifest, $toolCount tools declared)"
                    else "MANIFEST UNREADABLE - every tool will be refused") + "\n" +
                "Accessibility: " + if (com.agent.ultra.AgentAccessibilityService.isRunning()) "connected" else "not connected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Tool names out of a recipe's stored step list, for display. */
private fun steps(json: String): String = try {
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).joinToString(" → ") { arr.getJSONObject(it).optString("tool") }
} catch (_: Exception) { "—" }


/** One capability, and whether Android has actually allowed it. */
private data class Capability(
    val label: String,
    val why: String,
    val permissions: List<String>,
)

private val CAPABILITIES = listOf(
    Capability("Voice", "Speaking to it, and the hands-free session.",
        listOf(android.Manifest.permission.RECORD_AUDIO)),
    Capability("Camera", "Taking a picture when asked.",
        listOf(android.Manifest.permission.CAMERA)),
    Capability("Texts", "Reading and sending SMS.",
        listOf(android.Manifest.permission.READ_SMS, android.Manifest.permission.SEND_SMS)),
    Capability("Contacts", "Looking someone up by name.",
        listOf(android.Manifest.permission.READ_CONTACTS)),
    Capability("Calendar", "Reading and adding events.",
        listOf(android.Manifest.permission.READ_CALENDAR)),
    Capability("Location", "Answering where you are.",
        listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION)),
    Capability("Notifications", "Scam warnings that stay until you read them, and what it is doing while it works.",
        listOf(android.Manifest.permission.POST_NOTIFICATIONS)),
)

/**
 * Ask for what is missing, and show what is already there.
 *
 * Each of these is refused by default and stays refused until Android is
 * asked. Nothing here changes what the agent may do — the app list and the
 * personal-data switch decide that. This only decides whether the phone lets
 * the app try at all.
 */
@Composable
private fun PermissionPanel(context: android.content.Context) {
    var tick by remember { mutableStateOf(0) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }
    // Coming back from Android's own settings screens: re-read what is granted.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun granted(perms: List<String>) = perms.all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val missing = remember(tick) { CAPABILITIES.filterNot { granted(it.permissions) } }

    Text(
        if (missing.isEmpty())
            "Everything below is allowed. The agent can still only enter the apps you ticked."
        else
            "Android refuses these until you allow them, and until then the matching " +
                "features do nothing at all. Allowing one does not let the agent use it — " +
                "the app list and the personal-data switch still decide that.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
    )
    Spacer(Modifier.height(8.dp))

    for (cap in CAPABILITIES) {
        val ok = remember(tick) { granted(cap.permissions) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(cap.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (ok) "Allowed. ${cap.why}" else "Not allowed. ${cap.why}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (!ok) {
                TextButton(onClick = { launcher.launch(cap.permissions.toTypedArray()) }) {
                    Text("Allow")
                }
            } else {
                Text("✓", color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (missing.size > 1) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = {
            launcher.launch(missing.flatMap { it.permissions }.distinct().toTypedArray())
        }) { Text("Allow all of the above") }
    }

    // Notification access is not a normal permission and cannot be requested.
    // Until now the only documented way to turn it on was an adb command,
    // which is not a thing to ask of anyone.
    val listeners = android.provider.Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners"
    ).orEmpty()
    val notifOk = listeners.contains(context.packageName)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Reading notifications", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (notifOk) "Allowed. Telling you what came in while you were away."
                else "Not allowed. Android keeps this one on its own screen — no app can " +
                    "ask for it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        if (notifOk) Text("✓", color = MaterialTheme.colorScheme.primary)
        else TextButton(onClick = {
            context.startActivity(
                android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Open") }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "Android will not let an app ask twice if you have refused it. If a request " +
            "no longer appears, open it in the phone's own settings instead.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    TextButton(onClick = {
        context.startActivity(
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + context.packageName),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }) { Text("Open this app in Android settings") }
}
