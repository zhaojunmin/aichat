package com.octos.aichat.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.SettingsStore
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.DiagnosticsReport
import com.octos.aichat.diagnostics.ReportInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The diagnostics preview: builds the export text off the main thread (it reads the log files). */
class DiagnosticsViewModel(app: Application, private val settings: SettingsStore) :
    AndroidViewModel(app) {

    private val _report = MutableStateFlow<String?>(null)

    /** The export text; null while it is being built. */
    val report: StateFlow<String?> = _report

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val text = withContext(Dispatchers.IO) {
                val snap = DiagLog.snapshot()
                DiagnosticsReport.render(
                    ReportInput(
                        generatedAtMs = System.currentTimeMillis(),
                        device = deviceInfo(ctx),
                        settings = diagnosticsSettings(settings.current()),
                        permissions = diagnosticsPermissions(ctx),
                        counts = snap?.counts.orEmpty(),
                        events = snap?.events.orEmpty(),
                        durations = snap?.durations.orEmpty(),
                    ),
                    chinese = ctx.resources.configuration.locales[0].language == "zh",
                )
            }
            _report.value = text
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DiagnosticsViewModel(
                    context.applicationContext as Application,
                    SettingsRepository.get(context),
                )
            }
        }
    }
}
