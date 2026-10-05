package com.agent.ultra.ui

import androidx.compose.foundation.clickable
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Rebuilt when the provider config changes (settings save bumps the key).
    val brain = remember(configVersion) { Brain(context.applicationContext, localEngine) }

    // Speak-back: off by default, switched on in Settings. The engine is built
    // once and torn down with the screen.
    val speaker = remember { Speaker(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    LaunchedEffect(brain) {
        brain.onAnswer = { text ->
            if (UltraPrefs.speakAnswers(context)) speaker.speak(text)
        }
    }

    var input by remember { mutableStateOf("") }
    val thinking by ChatStore.thinking
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

    LaunchedEffect(ChatStore.messages.size) {
        if (ChatStore.messages.isNotEmpty()) listState.animateScrollToItem(ChatStore.messages.size - 1)
    }

    val currentTitle = ChatStore.conversations
        .find { it.id == ChatStore.conversationId.value }?.title ?: "Agent Ultra"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextButton(onClick = {
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
                                    .clickable {
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
                                TextButton(onClick = {
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
            TextButton(onClick = {
                torchOn = !torchOn
                val cmd = if (torchOn) "turn on the flashlight" else "turn off the flashlight"
                ChatStore.add(ChatMessage(true, cmd))
                ChatStore.agentScope.launch { brain.run(cmd) }
            }) { Text(if (torchOn) "🔦 on" else "🔦") }
            TextButton(onClick = {
                val cmd = "take a screenshot"
                ChatStore.add(ChatMessage(true, cmd))
                ChatStore.agentScope.launch { brain.run(cmd) }
            }) { Text("📸") }
            TextButton(onClick = {
                val cmd = "what is my location"
                ChatStore.add(ChatMessage(true, cmd))
                ChatStore.agentScope.launch { brain.run(cmd) }
            }) { Text("📍") }
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
                        Button(onClick = { scope.launch { brain.resolvePending(true) } }) {
                            Text("Confirm")
                        }
                        TextButton(onClick = { scope.launch { brain.resolvePending(false) } }) {
                            Text("Cancel")
                        }
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
                placeholder = { Text("Ask Agent Ultra…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            // Voice input — on-device speech recognition fills the box
            var listening by remember { mutableStateOf(false) }
            val speechHelper = remember {
                VoiceInput(context) { spoken -> input = (input + " " + spoken).trim() }
            }
            TextButton(onClick = {
                if (listening) {
                    speechHelper.stop()
                    listening = false
                } else {
                    listening = speechHelper.start()
                }
            }) {
                Text(if (listening) "🎙…" else "🎙")
            }
            Button(
                enabled = !thinking,
                onClick = {
                    val text = input.trim()
                    if (text.isEmpty()) return@Button
                    ChatStore.add(ChatMessage(fromUser = true, text = text))
                    input = ""
                    ChatStore.thinking.value = true
                    ChatStore.agentScope.launch {
                        try {
                            if (text.startsWith("/local ")) {
                                // Dev path: raw on-device generation
                                val prompt = text.removePrefix("/local ").trim()
                                val msg = ChatMessage(false, "")
                                ChatStore.addToState(msg)
                                val r = localEngine.generate(prompt, 300) { piece ->
                                    ChatStore.appendTo(msg.id, piece)
                                }
                                r.onFailure { ChatStore.setText(msg.id, "Error: ${it.message}") }
                                ChatStore.messageById(msg.id)?.let { ChatStore.persist(it) }
                            } else {
                                brain.run(text)
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ChatStore.add(ChatMessage(false, "Error: brain fault — ${e.message}"))
                        } finally {
                            ChatStore.thinking.value = false
                        }
                    }
                },
            ) {
                Text("Send")
            }
        }
    }
    }
    }
}
