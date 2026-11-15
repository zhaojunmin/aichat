package com.octos.aichat

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.provider.Settings
import android.util.Log
import com.octos.aichat.accessibility.AccessibilityBridge
import com.octos.aichat.ai.local.ModelStore
import com.octos.aichat.bubble.FloatingBubbleService
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.SettingsStore
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.DiagnosticsRecorder
import com.octos.aichat.diagnostics.DiagnosticsStore
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.ExitReasons
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-wide setup that must run before any component: diagnostics. Wiring the
 * switch here keeps the `diagnostics` package free of any dependency on `data`.
 *
 * Also owns the floating assistant ([FloatingBubbleService], one per process): it is
 * created while the accessibility service is connected, the bubble is switched on and
 * drawing over apps is allowed, and destroyed as soon as one of those stops holding.
 */
class ChatAssistantApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Overlay windows are added and removed on the main thread.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Drawing over apps can be granted with no callback to us: bumped to re-check.
    private val overlayCheck = MutableStateFlow(0)
    private var bubbleService: FloatingBubbleService? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.install(DiagnosticsRecorder(DiagnosticsStore(File(filesDir, "diagnostics"))))
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            DiagLog.echo = { Log.d("Diag", it) }
        }
        installCrashRecorder()
        // Lets the on-device engine find downloaded models without a Context threaded
        // through the AI layer (paths only).
        ModelStore.init(this)

        val settings = SettingsRepository.get(this)
        applyDiagnosticsSwitch(
            settings.current().diagnosticsEnabled,
            settings.current().diagnosticsEnabledAt,
        )
        scope.launch {
            settings.state
                .map { it.diagnosticsEnabled to it.diagnosticsEnabledAt }
                .distinctUntilChanged()
                .drop(1) // the current value was applied synchronously above
                .collect { (on, since) -> applyDiagnosticsSwitch(on, since) }
        }
        followBubble(settings)
    }

    /** The system theme flipped (or another config change): the bubble re-resolves its colours. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubbleService?.onConfigurationChanged()
    }

    /** Re-check whether the bubble should be up — e.g. after the overlay permission was granted. */
    fun refreshBubble() {
        overlayCheck.update { it + 1 }
    }

    private fun followBubble(settings: SettingsStore) {
        mainScope.launch {
            combine(
                AccessibilityBridge.screen.map { it != null },
                settings.state.map { it.bubbleAutoStart },
                overlayCheck,
            ) { connected, switchedOn, _ ->
                connected && switchedOn && Settings.canDrawOverlays(this@ChatAssistantApp)
            }
                .distinctUntilChanged()
                .collect { on -> if (on) createBubble() else destroyBubble() }
        }
    }

    private fun createBubble() {
        if (bubbleService != null) return
        bubbleService = runCatching { FloatingBubbleService(this) }
            .onFailure { DiagLog.record(ErrorCode.BUB_503, cause = it) }
            .getOrNull()
    }

    private fun destroyBubble() {
        bubbleService?.destroy()
        bubbleService = null
    }

    private fun applyDiagnosticsSwitch(on: Boolean, sinceMs: Long) {
        DiagLog.setEnabled(on)
        if (on) DiagLog.recordExits(sinceMs) { ExitReasons.read(this) }
    }

    /** Record APP-901 before the crash proceeds; the platform's handler still runs. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { DiagLog.recordCrash(e, thread) }
            previous?.uncaughtException(thread, e)
        }
    }
}
