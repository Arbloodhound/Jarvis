package com.agent.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.unit.dp
import com.agent.jarvis.local.LocalModelEngine
import com.agent.jarvis.ui.ChatScreen
import com.agent.jarvis.ui.SettingsScreen
import com.agent.jarvis.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply a seeded setup file here too, not only when the service connects.
        AgentAccessibilityService.applySetupFile(this)
        enableEdgeToEdge()
        setContent {
            JarvisTheme {
                var screen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("chat") }
                var guideFrom by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("chat") }
                // Shared engine — one JNI handle, never two 800MB loads.
                // Process-wide, so the voice session reuses this same context.
                val engine = remember { LocalModelEngine.shared(applicationContext) }
                // Bump on provider save so the brain rebuilds with the new config.
                var configVersion by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
                // "Open my notes" said or typed to the brain (MemoryFocus.screen): show it.
                val asked by com.agent.jarvis.ui.ChatStore.screenRequest
                androidx.compose.runtime.LaunchedEffect(asked) {
                    asked?.let { screen = it; com.agent.jarvis.ui.ChatStore.screenRequest.value = null }
                }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                when (screen) {
                    "chat" -> ChatScreen(
                        localEngine = engine,
                        configVersion = configVersion,
                        onOpenSettings = { screen = "settings" },
                        onOpenNotes = { screen = "notes" },
                        onOpenMemory = { screen = "memory" },
                        onOpenGuide = { guideFrom = "chat"; screen = "guide" },
                        onOpenInsights = { screen = "insights" },
                        onOpenForYou = { screen = "foryou" },
                        onModelChanged = { configVersion++ },
                    )
                    "foryou" -> com.agent.jarvis.ui.ForYouScreen(onBack = { screen = "chat" })
                    "insights" -> com.agent.jarvis.ui.InsightsScreen(onBack = { screen = "chat" })
                    "guide" -> com.agent.jarvis.ui.GuideScreen(onBack = { screen = guideFrom })
                    "notes" -> com.agent.jarvis.ui.NotesScreen(onBack = { screen = "chat" }, onAsk = { screen = "chat" })
                    "memory" -> com.agent.jarvis.ui.MemoryScreen(onBack = { screen = "chat" }, onAsk = { screen = "chat" })
                    "settings" -> SettingsScreen(
                        localEngine = engine,
                        onConfigSaved = { configVersion++ },
                        onBack = { screen = "chat" },
                        onOpenGuide = { guideFrom = "settings"; screen = "guide" },
                    )
                }
                // Floating diagnostic button, over every screen. Off by default.
                com.agent.jarvis.ui.DiagnosticFab(
                    modifier = androidx.compose.ui.Modifier
                        .align(androidx.compose.ui.Alignment.BottomEnd)
                        .systemBarsPadding()
                        .padding(16.dp),
                )
                }
            }
        }
    }

    override fun onPause() {
        com.agent.jarvis.ui.ChatStore.uiVisible = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        com.agent.jarvis.ui.ChatStore.uiVisible = true
        if (com.agent.jarvis.ui.JarvisPrefs.preloadBrain(this)) {
            LocalModelEngine.shared(applicationContext).preloadInBackground("app opened")
        }
        // Ambient listening the user left on but that isn't running (Android 15+ refuses to start
        // it at boot; a mic permission granted later): start it now, from the foreground.
        com.agent.jarvis.listen.AmbientListenService.resumeIfOff(this)
    }
}
