package com.octos.aichat

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.bubble.FloatingBubbleService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Auto-start the floating bubble on a fresh launch so the user doesn't tap
        // every time (only when enabled and overlay permission is granted).
        // savedInstanceState == null skips config-change recreations (e.g. rotation).
        if (savedInstanceState == null &&
            SettingsRepository.get(this).current().bubbleAutoStart &&
            Settings.canDrawOverlays(this)
        ) {
            FloatingBubbleService.start(this)
        }
        setContent { AppRoot() }
    }
}

/** State-based nav (no nav library): home, advanced, and memory management. */
private enum class Screen { Home, Advanced, Memory }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val repo = remember { SettingsRepository.get(context) }
    var screen by remember { mutableStateOf(Screen.Home) }
    MaterialTheme {
        when (screen) {
            Screen.Home -> HomeScreen(
                repo,
                onOpenAdvanced = { screen = Screen.Advanced },
                onOpenMemory = { screen = Screen.Memory },
            )
            Screen.Advanced -> AdvancedScreen(repo, onBack = { screen = Screen.Home })
            Screen.Memory -> MemoryScreen(onBack = { screen = Screen.Home })
        }
    }
}

