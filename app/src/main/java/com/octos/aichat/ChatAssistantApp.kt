package com.octos.aichat

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.provider.Settings
import android.util.Log
import com.octos.aichat.accessibility.AccessibilityBridge
import com.octos.aichat.ai.local.ModelStore
import com.octos.aichat.bubble.BubbleController
import com.octos.aichat.bubble.KeepLiveService
import com.octos.aichat.data.SettingsRepository
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
 * Also owns the floating bubble ([BubbleController], one per process): it is
 * created while the accessibility service is connected and drawing over apps is allowed,
 * and destroyed as soon as either stops holding.
 * While it is up, [KeepLiveService] keeps the process alive.
 */
class ChatAssistantApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Overlay windows are added and removed on the main thread.
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Drawing over apps can be granted with no callback to us: bumped to re-check.
    private val overlayCheck = MutableStateFlow(0)
    private var bubbleController: BubbleController? = null

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
        followBubble()
    }

    /** The system theme flipped (or another config change): the bubble re-resolves its colours. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubbleController?.onConfigurationChanged()
    }

    /** Re-check whether the bubble should be up — e.g. after the overlay permission was granted. */
    fun refreshBubble() {
        overlayCheck.update { it + 1 }
    }

    private fun followBubble() {
        mainScope.launch {
            combine(AccessibilityBridge.screen.map { it != null }, overlayCheck) { connected, _ ->
                connected && Settings.canDrawOverlays(this@ChatAssistantApp)
            }
                .distinctUntilChanged()
                .collect { on -> if (on) createBubble() else destroyBubble() }
        }
    }

    private fun createBubble() {
        if (bubbleController != null) return
        bubbleController = runCatching { BubbleController(this) }
            .onFailure { DiagLog.record(ErrorCode.BUB_503, cause = it) }
            .getOrNull()
        if (bubbleController != null) KeepLiveService.start(this)
    }

    private fun destroyBubble() {
        KeepLiveService.stop(this)
        bubbleController?.destroy()
        bubbleController = null
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
