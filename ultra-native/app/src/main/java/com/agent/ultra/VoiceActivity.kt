package com.agent.ultra

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
import androidx.lifecycle.lifecycleScope
import com.agent.ultra.agent.Brain
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.ui.ChatMessage
import com.agent.ultra.ui.ChatStore
import com.agent.ultra.ui.Speaker
import com.agent.ultra.ui.VoiceInput
import com.agent.ultra.ui.theme.AgentUltraTheme
import kotlinx.coroutines.launch

/**
 * The ear — a hands-free session.
 *
 * Registered for ACTION_ASSIST, so once Ultra is the device's digital
 * assistant the power-button long-press (or the assist gesture) opens straight
 * into listening. It shows over the lock screen: talk, get an answer spoken
 * back, done. Everything said here lands in the normal conversation history.
 *
 * `adb shell input keyevent KEYCODE_ASSIST` fires the same system event the
 * gesture does, which is how this path gets tested without a human hand.
 */
class VoiceActivity : ComponentActivity() {

    private lateinit var speaker: Speaker
    private var micPermission by mutableStateOf(false)

    /** Bumped every time the assist gesture arrives. The activity is
     * singleTask, so a second gesture reuses this instance and onCreate never
     * runs again — without this the mic would only ever open once. */
    private var sessionTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        speaker = Speaker(this)

        micPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val askMic = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> micPermission = granted }

        setContent {
            AgentUltraTheme {
                var state by remember { mutableStateOf("starting") }
                var heard by remember { mutableStateOf("") }
                var reply by remember { mutableStateOf("") }

                val engine = remember { LocalModelEngine.shared(applicationContext) }
                val brain = remember {
                    Brain(applicationContext, engine).also { b ->
                        b.onAnswer = { text ->
                            reply = text
                            state = "speaking"
                            // Mute the ambient mic while we speak, so TTS isn't re-heard.
                            // Bounded: if TTS never calls back (error/interrupt) the mic still returns.
                            com.agent.ultra.listen.AmbientListenService.instance?.let {
                                it.mutedUntil = System.currentTimeMillis() +
                                    com.agent.ultra.listen.EchoGuard.muteMsFor(text)
                            }
                            speaker.speak(text) {
                                state = "done"
                                com.agent.ultra.listen.AmbientListenService.instance?.let {
                                    it.mutedUntil = System.currentTimeMillis() +
                                        com.agent.ultra.listen.AmbientConfig.ECHO_GUARD_MS
                                }
                            }
                        }
                    }.also { b ->
                        // Ambient "Ultra, ..." commands run on this same brain instance.
                        com.agent.ultra.listen.AmbientBridge.onCommand = { request ->
                            lifecycleScope.launch { try { b.run(request) } catch (_: Exception) {} }
                        }
                    }
                }

                val listener = remember {
                    VoiceInput(
                        context = this@VoiceActivity,
                        onState = { s -> state = s },
                        onError = { msg ->
                            state = "error"
                            reply = msg
                            speaker.speak(msg) { state = "done" }
                        },
                    ) { spoken ->
                        heard = spoken
                        state = "working"
                        ChatStore.add(ChatMessage(fromUser = true, text = spoken))
                        lifecycleScope.launch {
                            try {
                                brain.run(spoken)
                            } catch (e: Exception) {
                                val m = "Something went wrong: ${e.message}"
                                reply = m
                                speaker.speak(m) { state = "done" }
                            }
                        }
                    }
                }

                fun listen() {
                    heard = ""; reply = ""
                    speaker.stop()
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
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        ) {
                            Button(onClick = { listen() }) { Text("Speak again") }
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
        speaker.shutdown()
        super.onDestroy()
    }
}
