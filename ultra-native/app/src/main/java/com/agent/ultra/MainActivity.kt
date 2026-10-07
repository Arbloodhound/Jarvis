package com.agent.ultra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.ui.ChatScreen
import com.agent.ultra.ui.SettingsScreen
import com.agent.ultra.ui.theme.AgentUltraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A seeded setup applies here too, not only when the service connects.
        // Toggling the accessibility binding to force a reconnect is unreliable
        // — Android often leaves the service listed but unbound — and the
        // activity and the service share a process, so applying it here updates
        // the very same state. This is what makes a device test one command
        // instead of four settings screens.
        AgentAccessibilityService.applySetupFile(this)
        enableEdgeToEdge()
        setContent {
            AgentUltraTheme {
                // Saveable: rotating inside Settings must not bounce back to chat.
                var screen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("chat") }
                var guideFrom by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("chat") }
                // Shared engine — one JNI handle, never two 800MB loads.
                // Process-wide, so the voice session reuses this same context.
                val engine = remember { LocalModelEngine.shared(applicationContext) }
                // Bump on provider save so the brain rebuilds with the new config.
                var configVersion by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
                // "Open my notes" said or typed to the brain (MemoryFocus.screen): show it.
                val asked by com.agent.ultra.ui.ChatStore.screenRequest
                androidx.compose.runtime.LaunchedEffect(asked) {
                    asked?.let { screen = it; com.agent.ultra.ui.ChatStore.screenRequest.value = null }
                }
                when (screen) {
                    "chat" -> ChatScreen(
                        localEngine = engine,
                        configVersion = configVersion,
                        onOpenSettings = { screen = "settings" },
                        onOpenNotes = { screen = "notes" },
                        onOpenMemory = { screen = "memory" },
                        onOpenGuide = { guideFrom = "chat"; screen = "guide" },
                    )
                    "guide" -> com.agent.ultra.ui.GuideScreen(onBack = { screen = guideFrom })
                    "notes" -> com.agent.ultra.ui.NotesScreen(onBack = { screen = "chat" }, onAsk = { screen = "chat" })
                    "memory" -> com.agent.ultra.ui.MemoryScreen(onBack = { screen = "chat" }, onAsk = { screen = "chat" })
                    "settings" -> SettingsScreen(
                        localEngine = engine,
                        onConfigSaved = { configVersion++ },
                        onBack = { screen = "chat" },
                        onOpenGuide = { guideFrom = "settings"; screen = "guide" },
                    )
                }
            }
        }
    }

    override fun onPause() {
        com.agent.ultra.ui.ChatStore.uiVisible = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        com.agent.ultra.ui.ChatStore.uiVisible = true
        if (com.agent.ultra.ui.UltraPrefs.preloadBrain(this)) {
            LocalModelEngine.shared(applicationContext).preloadInBackground("app opened")
        }
        // Ambient listening the user left on but that isn't running (Android 15+ refuses to start
        // it at boot; a mic permission granted later): start it now, from the foreground.
        com.agent.ultra.listen.AmbientListenService.resumeIfOff(this)
    }
}
