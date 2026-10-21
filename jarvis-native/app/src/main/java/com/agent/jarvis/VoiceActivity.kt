package com.agent.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.agent.jarvis.agent.Brain
import com.agent.jarvis.local.LocalModelEngine
import com.agent.jarvis.ui.ChatMessage
import com.agent.jarvis.ui.ChatStore
import com.agent.jarvis.ui.Speaker
import com.agent.jarvis.ui.VoiceInput
import com.agent.jarvis.ui.theme.JarvisTheme
import kotlinx.coroutines.launch
import com.agent.jarvis.ui.queue

/**
 * The ear — a hands-free session.
 *
 * Registered for ACTION_ASSIST, so once Jarvis is the device's digital
 * assistant the power-button long-press (or the assist gesture) opens straight
 * into listening. It shows over the lock screen: talk, get an answer spoken
 * back, done. Everything said here lands in the normal conversation history.
 *
 * `adb shell input keyevent KEYCODE_ASSIST` fires the same system event the
 * gesture does, which is how this path gets tested without a human hand.
 */
class VoiceActivity : ComponentActivity() {

    private lateinit var speaker: Speaker
    private lateinit var speech: com.agent.jarvis.ui.SpeechQueue
    private var micPermission by mutableStateOf(false)

    /** Bumped every time the assist gesture arrives. The activity is
     * singleTask, so a second gesture reuses this instance and onCreate never
     * runs again — without this the mic would only ever open once. */
    private var sessionTick by mutableIntStateOf(0)

    private var ambientHandler: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        speaker = Speaker(this)
        speech = speaker.queue()

        micPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val askMic = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> micPermission = granted }

        setContent {
            JarvisTheme {
                var state by remember { mutableStateOf("starting") }
                var heard by remember { mutableStateOf("") }
                var reply by remember { mutableStateOf("") }

                val engine = remember { LocalModelEngine.shared(applicationContext) }
                /** True while a run is in flight. */
                var running by remember { mutableStateOf(false) }
                remember {
                    // Mute the ambient mic while we speak, so TTS isn't re-heard. Bounded: if TTS
                    // never calls back (error/interrupt) the mic still returns.
                    speech.onStart = { text ->
                        runOnUiThread { state = "speaking" }
                        com.agent.jarvis.listen.AmbientListenService.instance?.let {
                            it.mutedUntil = System.currentTimeMillis() + com.agent.jarvis.listen.EchoGuard.muteMsFor(text)
                        }
                    }
                    speech.onIdle = {
                        runOnUiThread { if (state == "speaking") state = if (running) "working" else "done" }
                        com.agent.jarvis.listen.AmbientListenService.instance?.let {
                            it.mutedUntil = System.currentTimeMillis() + com.agent.jarvis.listen.AmbientConfig.ECHO_GUARD_MS
                        }
                    }
                    true
                }
                val brain = remember {
                    Brain(applicationContext, engine).also { b ->
                        // Spoken: every answer is said out loud, sentence by sentence as it is written.
                        b.maxThinkingDepth = com.agent.jarvis.agent.WaitBudget.Depth.QUICK
                        b.spokenAlways = true
                        b.onAnswer = { text -> reply = text }
                        b.onSpeak = { text -> speech.add(text) }
                    }
                }

                fun say(msg: String) {
                    reply = msg
                    speech.add(msg)
                }

                /**
                 * Every brain call from this session. Never while the phone is
                 * locked: the session shows over the lock screen, and the agent
                 * can act on anything, so unlock first. The run lives on the app
                 * scope — rotation or "Done" must not cut a task off mid-action.
                 */
                fun runBrain(block: suspend () -> Unit) {
                    whenUnlocked(onLocked = { say("Unlock your phone to continue.") }) {
                        running = true
                        state = "working"
                        ChatStore.agentScope.launch {
                            try {
                                block()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Never "…: null"; trimmed for speech; no detail on a locked
                                // phone (the run may outlast the unlock, and the detail can
                                // carry private content).
                                val km = getSystemService(android.app.KeyguardManager::class.java)
                                say(com.agent.jarvis.listen.CommandFailure.reply(e, withDetail = km?.isKeyguardLocked != true))
                            } finally {
                                running = false
                                if (!speech.busy && state == "working") state = "done"
                            }
                        }
                    }
                }

                /** Runs [request] once the run in progress has ended (or stopped). */
                fun runAfter(request: String) {
                    ChatStore.agentScope.launch {
                        while (com.agent.jarvis.agent.Brain.anyRunning) kotlinx.coroutines.delay(150)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { runBrain { brain.run(request) } }
                    }
                }

                /** What was said: a request, or, mid-run, a change to the run ([com.agent.jarvis.agent.Interrupt]). */
                fun heardRequest(spoken: String) {
                    val running = com.agent.jarvis.agent.Brain.runningRequest
                    when (val d = com.agent.jarvis.agent.Interrupt.decide(spoken, running)) {
                        com.agent.jarvis.agent.Interrupt.Decision.Stop -> {
                            com.agent.jarvis.agent.Brain.stopAll()
                            com.agent.jarvis.ui.SpeechQueue.stopAll()
                            say("Stopped.")
                        }
                        is com.agent.jarvis.agent.Interrupt.Decision.Run -> runBrain { brain.run(d.request) }
                        is com.agent.jarvis.agent.Interrupt.Decision.After -> { say("Okay, after this."); runAfter(d.request) }
                        is com.agent.jarvis.agent.Interrupt.Decision.Replace -> {
                            com.agent.jarvis.agent.Brain.stopAll()
                            com.agent.jarvis.ui.SpeechQueue.stopAll()
                            runAfter(d.request)
                        }
                    }
                }

                // Ambient "Jarvis, ..." commands run on this same brain instance.
                remember(brain) {
                    val handler: (String) -> Unit = { request -> runBrain { brain.run(request) } }
                    ambientHandler = handler
                    com.agent.jarvis.listen.AmbientBridge.onCommand = handler
                    handler
                }

                val listener = remember {
                    VoiceInput(
                        context = this@VoiceActivity,
                        onState = { s -> state = s },
                        onError = { msg ->
                            state = "error"
                            reply = msg
                            speech.add(msg)
                        },
                    ) { spoken ->
                        heard = spoken
                        state = "working"
                        ChatStore.add(ChatMessage(fromUser = true, text = spoken))
                        heardRequest(spoken)
                    }
                }
                // The recognizer is a system service binding; let it go with the session.
                androidx.compose.runtime.DisposableEffect(listener) { onDispose { listener.stop() } }

                // A policy-gate pause waits on the user: Brain answers (and so speaks) what is
                // waiting; Confirm / Cancel below, or the session sits on "Working…".
                val pending = brain.pendingConfirm

                fun listen() {
                    heard = ""; reply = ""
                    // Jarvis stops talking when you go to speak (the mic must not hear it); a run
                    // goes on quietly, and what you say next can stop or change it.
                    com.agent.jarvis.agent.Brain.hushAll()
                    com.agent.jarvis.ui.SpeechQueue.stopAll()
                    state = if (listener.start()) "listening" else "error"
                }

                // Open the mic the moment the session appears — the whole point
                // is that the user never taps anything.
                androidx.compose.runtime.LaunchedEffect(micPermission, sessionTick) {
                    ChatStore.init(applicationContext)
                    if (micPermission) listen()
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = when (state) {
                                "downloading" -> "Getting the speech model…"
                                "listening" -> "🎙  Listening…"
                                "hearing" -> "🎙  …"
                                "thinking", "working" -> "Working…"
                                "speaking" -> "🔊"
                                "error" -> "—"
                                else -> "…"
                            },
                            style = MaterialTheme.typography.displaySmall,
                        )
                        if (heard.isNotEmpty()) {
                            Text(
                                "“$heard”",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                        if (reply.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 20.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Text(reply, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (pending != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            ) {
                                Button(enabled = !running, onClick = {
                                    speech.stop()
                                    runBrain { brain.resolvePending(true) }
                                }) { Text("Confirm") }
                                TextButton(enabled = !running, onClick = {
                                    speech.stop()
                                    runBrain { brain.resolvePending(false) }
                                }) { Text("Cancel") }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        ) {
                            // Mid-run too: speak to stop or change what it is doing.
                            Button(onClick = { listen() }) { Text("Speak again") }
                            if (running) TextButton(onClick = {
                                com.agent.jarvis.agent.Brain.stopAll()
                                com.agent.jarvis.ui.SpeechQueue.stopAll()
                            }) { Text("Stop") }
                            TextButton(onClick = { finish() }) { Text("Done") }
                        }
                    }
                }
            }
        }
    }

    /** A repeat assist gesture lands here, not in onCreate. Start listening again. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sessionTick++
    }

    /**
     * Run [block] only on an unlocked phone. Showing over the lock screen is
     * fine; acting for whoever is holding a locked phone is not. When locked,
     * ask Android to dismiss the keyguard (the user unlocks) and continue only
     * on success.
     */
    private fun whenUnlocked(onLocked: () -> Unit, block: () -> Unit) {
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { block(); return }
        km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { block() }
            override fun onDismissCancelled() { onLocked() }
            override fun onDismissError() { onLocked() }
        })
    }

    /** The assist gesture fires with the phone locked — meet it there. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onDestroy() {
        // Hand ambient commands back to the listening service's own brain; leaving
        // ours set would launch on a dead lifecycle (dropped) and keep this Activity alive.
        if (com.agent.jarvis.listen.AmbientBridge.onCommand === ambientHandler) {
            com.agent.jarvis.listen.AmbientBridge.onCommand = null
        }
        com.agent.jarvis.ui.SpeechQueue.unregister(speech)
        speaker.shutdown()
        super.onDestroy()
    }
}
