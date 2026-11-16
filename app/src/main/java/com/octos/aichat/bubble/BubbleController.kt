package com.octos.aichat.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import com.octos.aichat.R
import com.octos.aichat.accessibility.AccessibilityBridge
import com.octos.aichat.capture.CaptureResult
import com.octos.aichat.capture.ChatCapture
import com.octos.aichat.capture.ReadPath
import com.octos.aichat.capture.ScreenSize
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.data.memory.MemoryRepository
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import com.octos.aichat.diagnostics.TimedOp
import com.octos.aichat.diagnostics.Tracing
import com.octos.aichat.ui.theme.ThemeVariant
import com.octos.aichat.ui.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Controls the floating bubble: a draggable bubble over other apps. Tapping it asks the
 * configured AI for suggestions based on what the accessibility reader sees, and shows
 * them in a panel ([BubblePanel]). Tapping one copies it (and optionally fills it into
 * the chat input).
 *
 * A single instance per process, created and destroyed by
 * [com.octos.aichat.ChatAssistantApp] — it exists while the accessibility service is
 * connected and drawing over apps is allowed. Construction shows the bubble; [destroy]
 * removes it.
 *
 * It owns the views and the collaborators, turns taps on the views into work (tap →
 * suggestions → chosen one delivered) and keeps the views in step with the foreground
 * app and the theme. The ball itself is [FloatingBubble], the panel [BubblePanel],
 * shared drawables [BubbleStyle].
 */
class BubbleController(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var generating: Job? = null
    private val suggestions = SuggestionGenerator()
    private var palette: BubblePalette = resolvePalette()
    private val panel = BubblePanel(context, wm, palette, ::onSuggestionChosen)
    private val bubble =
        FloatingBubble(context, wm, palette, onTap = ::onBubbleTapped) { wasShowing ->
            // The user left the chat app: a good moment to learn, since a local
            // learning pass can't hold up suggestions they're waiting for.
            if (wasShowing) MemoryRepository.get(context).learnIfDue()
            panel.remove()
        }
    private val capture = ChatCapture(
        screen = { AccessibilityBridge.screen.value },
        overlay = bubble,
        screenSize = {
            context.resources.displayMetrics.let { ScreenSize(it.widthPixels, it.heightPixels) }
        },
    )

    init {
        followThemeSetting()
        followForegroundApp()
    }

    /** "System" theme follows the device's dark mode, so re-resolve when it flips. */
    fun onConfigurationChanged() {
        applyPalette(resolvePalette())
    }

    /** Remove the bubble and the panel and stop all work; this instance is done after. */
    fun destroy() {
        generating?.cancel()
        scope.cancel()
        panel.remove()
        bubble.remove()
    }

    /** Show the bubble inside target apps only ([FloatingBubble.setVisible] debounces the hide). */
    private fun followForegroundApp() {
        scope.launch {
            AccessibilityBridge.foreground.filterNotNull().collect {
                bubble.setVisible(it.isTarget)
            }
        }
    }

    // ---- Theme -----------------------------------------------------------

    private fun resolvePalette(): BubblePalette {
        val systemDark =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val variantId = SettingsRepository.get(context).current().themeVariantId
        return BubblePalette.from(ThemeVariant.resolve(variantId, systemDark).colorScheme)
    }

    /** Re-colour the bubble live when the theme is changed in settings. */
    private fun followThemeSetting() {
        scope.launch {
            SettingsRepository.get(context).state
                .map { it.themeVariantId }
                .distinctUntilChanged()
                .collect { applyPalette(resolvePalette()) }
        }
    }

    private fun applyPalette(next: BubblePalette) {
        if (next == palette) return
        palette = next
        panel.palette = next
        bubble.palette = next
    }

    // ---- Orchestration ---------------------------------------------------

    private fun onBubbleTapped() {
        if (panel.isShowing) {
            panel.remove()
            return
        }
        val settings = SettingsRepository.get(context).current()
        if (!settings.useLocal && settings.apiKey.isBlank()) {
            toast(context.getString(R.string.svc_need_api_key))
            return
        }
        generating?.cancel()
        generating = scope.launch {
            Tracing.asyncSection(Tracing.BUBBLE_TAP_TO_RESULT) { generateForTap(settings) }
        }
    }

    /** Tap → suggestions (or an error) on the panel. Split out so it can be traced as one span. */
    private suspend fun generateForTap(settings: AppSettings) {
        val tappedAt = SystemClock.elapsedRealtime()
        // Resolve the conversation first (may involve a screenshot); only then
        // show the loading panel so it isn't captured into the screenshot.
        val read = Tracing.asyncSection(Tracing.BUBBLE_RESOLVE_CONVERSATION) {
            resolveConversation(settings)
        }
        val conv = read.conversation
        panel.showLoading()
        if (conv == null || conv.isEmpty) {
            // The hint follows the path actually taken, not a setting.
            val hint = when (read.path) {
                ReadPath.OCR -> R.string.svc_ocr_no_content
                ReadPath.NODE_TREE -> R.string.svc_no_content
            }
            panel.showError(context.getString(hint))
            return
        }
        // Local-mode problems (unsupported device, model not downloaded, load failure)
        // come back from the AiClient as an AiException, shown like any other failure.
        val result = runCatching {
            suggestions.suggestFor(settings, conv, MemoryRepository.get(context))
        }
        // Superseded by a newer tap: don't paint an error over the new request.
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        result.onSuccess {
            panel.showSuggestions(it, conv.lastFromOther())
            DiagLog.recordDuration(TimedOp.TAP_TO_RESULT, SystemClock.elapsedRealtime() - tappedAt)
        }
            .onFailure { panel.showError(it.toUserMessage(context)) }
    }

    /** What's on screen this moment, via the node tree or (OCR-list apps) a screenshot. */
    private suspend fun resolveConversation(settings: AppSettings): CaptureResult =
        capture.read(AccessibilityBridge.foregroundPackage ?: "", settings.ocrPackageSet)

    private fun onSuggestionChosen(suggestion: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_suggestion", suggestion))

        panel.remove()
        val autoPaste = SettingsRepository.get(context).current().autoPaste
        if (!autoPaste) {
            toast(context.getString(R.string.svc_copied))
            return
        }
        scope.launch {
            val pkg = AccessibilityBridge.foregroundPackage
            val screen = AccessibilityBridge.screen.value
            val pasted = screen != null && screen.fill(suggestion, pkg)
            if (screen == null) DiagLog.record(ErrorCode.A11Y_305, pkg = pkg)
            toast(context.getString(if (pasted) R.string.svc_filled else R.string.svc_copied))
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
