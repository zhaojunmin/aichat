package com.octos.aichat.ui
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octos.aichat.R
import com.octos.aichat.ai.AiService
import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.ai.local.LocalModel
import com.octos.aichat.ai.local.ModelStore
import com.octos.aichat.ai.local.localModel
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.ChatMessage
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.SettingsStore
import com.octos.aichat.data.Speaker
import com.octos.aichat.data.memory.MemoryRepository
import com.octos.aichat.data.memory.MemoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Home screen state + side-effects: settings, memory stats, the connection test, and
 * local-model download/delete. Depends on [SettingsStore]/[MemoryStore] abstractions;
 * platform bits that genuinely need a Context (model files) use the application context.
 * Coroutines run in [viewModelScope], keeping the composable free of them.
 */
class HomeViewModel(
    app: Application,
    private val settings: SettingsStore,
    private val memory: MemoryStore,
) : AndroidViewModel(app) {

    val settingsState = settings.state
    val memories = memory.memories
    val stats = memory.stats

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing
    private val _testOutput = MutableStateFlow<List<String>?>(null)
    val testOutput: StateFlow<List<String>?> = _testOutput

    private val _downloading = MutableStateFlow(false)
    val downloading: StateFlow<Boolean> = _downloading
    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress
    private val _modelError = MutableStateFlow<String?>(null)
    val modelError: StateFlow<String?> = _modelError

    // Bumped after download/delete so the UI re-checks model readiness.
    private val _readyTick = MutableStateFlow(0)
    val readyTick: StateFlow<Int> = _readyTick

    private val appContext: Context get() = getApplication()

    fun update(transform: (AppSettings) -> AppSettings) = settings.update(transform)

    fun learnNow() = viewModelScope.launch { memory.learnNow() }
    fun clearMemory() = viewModelScope.launch { memory.clearAll() }

    fun isModelReady(model: LocalModel): Boolean = ModelStore.isReady(appContext, model)

    fun clearModelError() {
        _modelError.value = null
    }

    fun downloadModel(model: LocalModel) {
        if (_downloading.value) return
        _downloading.value = true
        _modelError.value = null
        _progress.value = appContext.getString(R.string.vm_connecting)
        viewModelScope.launch {
            val r = ModelStore.download(appContext, model) { p ->
                _progress.value = "${p.percent}% · ${p.file} (${p.index}/${p.total}) " +
                    "${p.doneBytes / 1_000_000}/${p.totalBytes / 1_000_000}MB"
            }
            _downloading.value = false
            if (r.isSuccess) {
                _progress.value = appContext.getString(R.string.vm_download_done)
            } else {
                _progress.value = ""
                _modelError.value = r.exceptionOrNull()?.message
                    ?: appContext.getString(R.string.vm_download_failed)
            }
            _readyTick.value++
        }
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            LlmEngine.unload()
            ModelStore.delete(appContext, model)
            _readyTick.value++
        }
    }

    fun dismissTest() {
        _testOutput.value = null
    }

    fun runConnectionTest() {
        if (_testing.value) return
        _testing.value = true
        viewModelScope.launch {
            val s = settings.current()
            val sample = Conversation(
                "test",
                listOf(
                    ChatMessage(Speaker.OTHER, appContext.getString(R.string.vm_sample_other_1)),
                    ChatMessage(Speaker.ME, appContext.getString(R.string.vm_sample_me)),
                    ChatMessage(Speaker.OTHER, appContext.getString(R.string.vm_sample_other_2)),
                ),
            )
            _testOutput.value = runCatching {
                if (s.useLocal) {
                    check(LlmEngine.ensureLoaded(appContext, s.localModel)) {
                        appContext.getString(R.string.error_local_load_failed)
                    }
                }
                AiService.instance.generateSuggestions(s, sample)
            }.getOrElse {
                listOf(appContext.getString(R.string.vm_error_prefix, it.toUserMessage(appContext)))
            }
            _testing.value = false
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    context.applicationContext as Application,
                    SettingsRepository.get(context),
                    MemoryRepository.get(context),
                )
            }
        }
    }
}
