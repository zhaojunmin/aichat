package com.octos.aichat.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octos.aichat.ChatAssistantApp
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.ui.theme.AiChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }

    // Back from the system settings, the overlay permission may have just been granted.
    override fun onResume() {
        super.onResume()
        (application as ChatAssistantApp).refreshBubble()
    }
}

/** State-based nav (no nav library): home, advanced (→ diagnostics), and memory management. */
private enum class Screen { Home, Advanced, Memory, Diagnostics }

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.Home) }
    val context = LocalContext.current
    val settings by SettingsRepository.get(context).state.collectAsStateWithLifecycle()
    AiChatTheme(settings.themeVariantId) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    onOpenAdvanced = { screen = Screen.Advanced },
                    onOpenMemory = { screen = Screen.Memory },
                )

                Screen.Advanced -> AdvancedScreen(
                    onBack = { screen = Screen.Home },
                    onOpenDiagnostics = { screen = Screen.Diagnostics },
                )

                Screen.Diagnostics -> DiagnosticsScreen(onBack = { screen = Screen.Advanced })

                Screen.Memory -> MemoryScreen(onBack = { screen = Screen.Home })
            }
        }
    }
}
