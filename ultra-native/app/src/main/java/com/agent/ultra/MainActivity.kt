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
                var screen by remember { mutableStateOf("chat") }
                // Shared engine — one JNI handle, never two 800MB loads.
                // Process-wide, so the voice session reuses this same context.
                val engine = remember { LocalModelEngine.shared(applicationContext) }
                // Bump on provider save so the brain rebuilds with the new config.
                var configVersion by remember { mutableIntStateOf(0) }
                when (screen) {
                    "chat" -> ChatScreen(
                        localEngine = engine,
                        configVersion = configVersion,
                        onOpenSettings = { screen = "settings" },
                    )
                    "settings" -> SettingsScreen(
                        localEngine = engine,
                        onConfigSaved = { configVersion++ },
                        onBack = { screen = "chat" },
                    )
                }
            }
        }
    }
}
