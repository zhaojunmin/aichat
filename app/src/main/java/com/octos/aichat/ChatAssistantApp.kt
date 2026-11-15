package com.octos.aichat

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.DiagnosticsRecorder
import com.octos.aichat.diagnostics.DiagnosticsStore
import com.octos.aichat.diagnostics.ExitReasons
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-wide setup that must run before any component: diagnostics. Wiring the
 * switch here keeps the `diagnostics` package free of any dependency on `data`.
 */
class ChatAssistantApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        DiagLog.install(DiagnosticsRecorder(DiagnosticsStore(File(filesDir, "diagnostics"))))
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            DiagLog.echo = { Log.d("Diag", it) }
        }
        installCrashRecorder()

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
