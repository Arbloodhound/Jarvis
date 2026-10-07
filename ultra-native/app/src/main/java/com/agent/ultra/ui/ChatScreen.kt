package com.agent.ultra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.agent.ultra.AgentAccessibilityService
import com.agent.ultra.agent.Brain
import com.agent.ultra.local.LocalModelEngine
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    localEngine: LocalModelEngine,
    configVersion: Int,
    onOpenSettings: () -> Unit,
    onOpenNotes: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenGuide: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val thinking by ChatStore.thinking
    // Process-level brain (see ChatStore.chatBrain): survives leaving this
    // screen mid-run, and is rebuilt for a new provider config (settings save
    // bumps the key) only once no run or confirmation is in flight. Speak-back
    // (off by default, switched on in Settings) is hooked up there.
    val brain = remember(configVersion, thinking) {
        ChatStore.chatBrain(context, localEngine, configVersion)
    }

    /** Every agent run from this screen: one at a time, marked as thinking,
     * owned by the app scope, and a fault becomes a message instead of a crash. */
    fun launchRun(block: suspend () -> Unit): Boolean {
        if (ChatStore.thinking.value) return false
        ChatStore.thinking.value = true
        ChatStore.agentScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ChatStore.add(ChatMessage(false, "Error: brain fault — ${e.message}"))
            } finally {
                ChatStore.thinking.value = false
            }
        }
        return true
    }

    /** Runs [request] now; false when another run holds the screen. "/local …" is the raw-model dev path. */
    fun start(request: String, spoken: Boolean): Boolean {
        val text = request
        return launchRun {
            if (text.startsWith("/local ")) {
                val prompt = text.removePrefix("/local ").trim()
                val msg = ChatMessage(false, "")
                ChatStore.addToState(msg)
                val r = localEngine.generate(prompt, 300) { piece -> ChatStore.appendTo(msg.id, piece) }
                r.onFailure { ChatStore.setText(msg.id, "Error: ${it.message}") }
                ChatStore.messageById(msg.id)?.let { ChatStore.persist(it) }
            } else {
                brain.run(text, spoken)
            }
        }
    }

    /** Runs [request] once the run in progress has ended (or stopped). */
    fun startAfter(request: String, spoken: Boolean) {
        ChatStore.agentScope.launch {
            while (true) {
                while (ChatStore.thinking.value) kotlinx.coroutines.delay(150)
                if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { start(request, spoken) }) break
            }
        }
    }

    /**
     * One request from this screen, typed or spoken ([spoken]: answered out loud): shown, then run.
     * While a run is going it changes that run instead (owner: "stop … or modify its current task"):
     * "stop" stops it and any speech, "also …" runs after it, anything else stops it and runs in its
     * place ([com.agent.ultra.agent.Interrupt]).
     */
    fun sendNow(text: String, spoken: Boolean = false) {
        ChatStore.add(ChatMessage(fromUser = true, text = text))
        val busy = ChatStore.thinking.value
        val running = if (busy) com.agent.ultra.agent.Brain.runningRequest.orEmpty() else null
        when (val d = com.agent.ultra.agent.Interrupt.decide(text, running)) {
            com.agent.ultra.agent.Interrupt.Decision.Stop -> {
                val ran = com.agent.ultra.agent.Brain.stopAll()
                val spoke = SpeechQueue.stopAll()
                ChatStore.add(ChatMessage(false, if (ran || spoke) "Stopped." else "Nothing to stop."))
            }
            is com.agent.ultra.agent.Interrupt.Decision.Run ->
                if (!start(d.request, spoken)) startAfter(d.request, spoken)
            is com.agent.ultra.agent.Interrupt.Decision.After -> {
                ChatStore.add(ChatMessage(false, "Okay — after this."))
                startAfter(d.request, spoken)
            }
            is com.agent.ultra.agent.Interrupt.Decision.Replace -> {
                com.agent.ultra.agent.Brain.stopAll()
                SpeechQueue.stopAll()
                startAfter(d.request, spoken)
            }
        }
    }

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var a11yRunning by remember { mutableStateOf(AgentAccessibilityService.isRunning()) }
    // Enabled in Android's settings, yet not bound. The switch looks on and
    // nothing works; only turning it off and on again fixes it, and nobody
    // would guess that from a switch that is already in the right position.
    var a11yStuck by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(Unit) { ChatStore.init(context.applicationContext) }

    // Back closes the drawer first rather than dropping straight out of the app.
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            a11yRunning = AgentAccessibilityService.isRunning()
            a11yStuck = AgentAccessibilityService.listedButNotBound(context)
            kotlinx.coroutines.delay(3000)
        }
    }

    // On-device model status ("Loading the brain…" / "Thinking…"): a line under the
    // chat, never a message. The clock ticks only while one is showing.
    val status by ChatStore.status
    var statusNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (status != null) {
            statusNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(ChatStore.messages.size) {
        if (ChatStore.messages.isNotEmpty()) listState.animateScrollToItem(ChatStore.messages.size - 1)
    }
    // Bring a newly shown status line into view; clearing it does not scroll.
    LaunchedEffect(status) {
        if (status != null) listState.animateScrollToItem(ChatStore.messages.size)
    }

    val currentTitle = ChatStore.conversations
        .find { it.id == ChatStore.conversationId.value }?.title ?: "Jarvis"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    // A run writes into whichever conversation is open, so the
                    // open one stays put until it finishes.
                    TextButton(enabled = !thinking, onClick = {
                        scope.launch {
                            ChatStore.newChat()
                            drawerState.close()
                        }
                    }) { Text("+ New chat", color = MaterialTheme.colorScheme.primary) }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    LazyColumn {
                        items(ChatStore.conversations, key = { it.id }) { conv ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !thinking) {
                                        scope.launch {
                                            ChatStore.openConversation(conv.id)
                                            drawerState.close()
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    conv.title,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(enabled = !thinking, onClick = {
                                    scope.launch { ChatStore.deleteConversation(conv.id) }
                                }) { Text("✕", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                            }
                        }
                    }
                }
            }
        },
    ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { scope.launch { drawerState.open() } }) {
                Text("☰", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = currentTitle,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    thinking -> "agent: thinking…"
                    a11yRunning -> "agent: ready"
                    a11yStuck -> "agent: switch it off and on ›"
                    else -> "agent: a11y off ›"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (a11yRunning || thinking)
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.error,
                modifier = if (a11yRunning || thinking) Modifier else Modifier.clickable {
                    // Off means every screen-touching tool fails. One tap to the fix.
                    try {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {}
                },
            )
            TextButton(onClick = onOpenSettings) {
                Text("⚙", style = MaterialTheme.typography.titleLarge)
            }
        }

        // Model chip + quick actions (drive through the agent's own local-first
        // router — these exercise the same path as typed commands)
        var torchOn by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // seven buttons and the model chip don't fit a phone's width: scroll sideways
                .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    brain.modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    maxLines = 1,
                )
            }
            TextButton(enabled = !thinking, onClick = {
                torchOn = !torchOn
                val cmd = if (torchOn) "turn on the flashlight" else "turn off the flashlight"
                ChatStore.add(ChatMessage(true, cmd))
                launchRun { brain.run(cmd) }
            }) { Text(if (torchOn) "🔦 on" else "🔦") }
            TextButton(enabled = !thinking, onClick = {
                val cmd = "take a screenshot"
                ChatStore.add(ChatMessage(true, cmd))
                launchRun { brain.run(cmd) }
            }) { Text("📸") }
            TextButton(enabled = !thinking, onClick = {
                val cmd = "what is my location"
                ChatStore.add(ChatMessage(true, cmd))
                launchRun { brain.run(cmd) }
            }) { Text("📍") }
            TextButton(onClick = onOpenNotes) { Text("📝") }
            TextButton(onClick = onOpenMemory) { Text("🧠") }
            TextButton(onClick = onOpenGuide) { Text("📖") }   // the complete guide
            // Hands-free session. Reachable here too, so it works before the
            // user has made Ultra their assistant app.
            TextButton(onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(context, com.agent.ultra.VoiceActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
            }) { Text("🎧") }
        }

        // First run: one tap fetches every model that's missing (Settings → DOWNLOADS has the list).
        DownloadsCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ChatStore.messages, key = { it.id }) { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        color = if (msg.fromUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = msg.text,
                            color = if (msg.fromUser) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            status?.let { s ->
                item(key = "status") {
                    Text(
                        text = ChatStatus.label(s.phase, statusNow - s.sinceMs, s.detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // Per-action gate: the navigator is about to tap something that commits.
        com.agent.ultra.agent.ActionGate.pending?.let { p ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "About to press something that commits",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "\u201C${p.label}\u201D",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Text(
                        "in ${p.app}  ·  matched \u201C${p.reason}\u201D",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    )
                    Text(
                        "Nothing happens unless you press Do it. Ignoring this cancels the action.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        Button(onClick = { com.agent.ultra.agent.ActionGate.resolve(true) }) {
                            Text("Do it")
                        }
                        TextButton(onClick = { com.agent.ultra.agent.ActionGate.resolve(false) }) {
                            Text("Don't")
                        }
                    }
                }
            }
        }

        // Policy-gate confirmation card (the resolve/confirm channel)
        brain.pendingConfirm?.let { pending ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "The policy gate paused this action:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        pending.description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    if (pending.targets.isNotEmpty()) {
                        Text(
                            "Target: " + pending.targets.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        Button(enabled = !thinking, onClick = { launchRun { brain.resolvePending(true) } }) {
                            Text("Confirm")
                        }
                        TextButton(enabled = !thinking, onClick = { launchRun { brain.resolvePending(false) } }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }

        // Focus: answer from Memory, Notes or both ([MemoryFocus]) instead of the model's own knowledge.
        var focusMenu by remember { mutableStateOf(false) }
        val focus by ChatStore.focus
        Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box {
                Surface(
                    color = if (focus == com.agent.ultra.agent.MemoryFocus.Focus.NORMAL) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.clickable { focusMenu = true },
                ) {
                    Text("Focus: ${focus.label} ▾", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
                androidx.compose.material3.DropdownMenu(expanded = focusMenu, onDismissRequest = { focusMenu = false }) {
                    for (f in com.agent.ultra.agent.MemoryFocus.Focus.entries) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column {
                                    Text(f.label)
                                    Text(when (f) {
                                        com.agent.ultra.agent.MemoryFocus.Focus.NORMAL -> "Its own knowledge; memory when you ask about the past"
                                        com.agent.ultra.agent.MemoryFocus.Focus.MEMORY -> "Every answer from what it heard"
                                        com.agent.ultra.agent.MemoryFocus.Focus.NOTES -> "Every answer from your notes"
                                        com.agent.ultra.agent.MemoryFocus.Focus.BOTH -> "From what it heard and your notes"
                                    }, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                            },
                            onClick = { ChatStore.focus.value = f; focusMenu = false },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(when (focus) {
                    com.agent.ultra.agent.MemoryFocus.Focus.MEMORY -> "Ask about what Jarvis heard…"
                    com.agent.ultra.agent.MemoryFocus.Focus.NOTES -> "Ask about your notes…"
                    com.agent.ultra.agent.MemoryFocus.Focus.BOTH -> "Ask about your memory and notes…"
                    else -> "Ask Jarvis…"
                }) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            // Voice input — on-device speech recognition fills the box
            var listening by remember { mutableStateOf(false) }
            val speechHelper = remember {
                VoiceInput(
                    context,
                    onError = { msg ->
                        listening = false
                        android.widget.Toast.makeText(context, "Voice: $msg", android.widget.Toast.LENGTH_SHORT).show()
                    },
                ) { spoken ->
                    listening = false
                    // Speaking is asking: what was said runs at once, like Send (owner, 2026-10-07: it only
                    // filled the box, so a spoken request did nothing until Send was pressed), and is
                    // answered out loud. Said mid-run, it stops or changes that run (sendNow).
                    val text = (input + " " + spoken).trim()
                    if (text.isNotEmpty()) { input = ""; sendNow(text, spoken = true) }
                }
            }
            DisposableEffect(speechHelper) { onDispose { speechHelper.stop() } }
            val askMic = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
            ) { granted -> if (granted) listening = speechHelper.start() }
            TextButton(onClick = {
                if (listening) {
                    speechHelper.stop()
                    listening = false
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.RECORD_AUDIO
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    // Jarvis stops talking when you go to speak (the mic must not hear it), and a run
                    // still answering stays quiet; what you say can then stop or change it.
                    com.agent.ultra.agent.Brain.hushAll()
                    SpeechQueue.stopAll()
                    listening = speechHelper.start()
                }
            }) {
                Text(if (listening) "🎙…" else "🎙")
            }
            // While anything runs (thinking, answering, driving another app): stop it and any
            // speech. Generation stops at the next token; any open confirmation is denied.
            if (thinking) {
                TextButton(onClick = {
                    com.agent.ultra.agent.Brain.stopAll()
                    SpeechQueue.stopAll()
                }) { Text("Stop") }
            }
            // While the on-device model thinks silently (up to minutes at Thinking depth: Deep):
            // end the thinking and get the answer from what it has so far. Not while "Thinking…"
            // only covers a memory search: there is no trace to end yet. (The trace is set active
            // just before its THINKING status, so this recomposition already sees it.)
            if (status?.phase == ChatStatus.Phase.THINKING && com.agent.ultra.agent.Brain.thinkingActive) {
                TextButton(onClick = { com.agent.ultra.agent.Brain.answerNow() }) { Text("Answer now") }
            }
            // Enabled mid-run too: what is sent then stops or changes the run (sendNow).
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty()) return@Button
                    input = ""
                    sendNow(text)
                },
            ) {
                Text("Send")
            }
        }
    }
    }
    }
}
