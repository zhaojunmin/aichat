package com.octos.aichat.bubble

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
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
import com.octos.aichat.ui.MainActivity
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
 * Foreground service that paints a draggable bubble over other apps. Tapping it
 * asks the configured AI for suggestions based on whatever the accessibility
 * reader last captured, and shows them in a panel ([BubblePanel]). Tapping a
 * candidate copies it (and optionally auto-pastes into the chat input).
 *
 * This class owns the service lifecycle and orchestration (tap → suggestions → chosen
 * one delivered). The ball itself is [FloatingBubble], the panel [BubblePanel], shared
 * drawables [BubbleStyle].
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubble: FloatingBubble
    private lateinit var panel: BubblePanel
    private lateinit var capture: ChatCapture
    private lateinit var palette: BubblePalette
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var generating: Job? = null
    private val suggestions = SuggestionGenerator()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        palette = resolvePalette()
        panel = BubblePanel(this, wm, palette, ::onSuggestionChosen)
        startForegroundNotice()
        bubble = FloatingBubble(this, wm, palette, onTap = ::onBubbleTapped) { wasShowing ->
            // The user left the chat app: a good moment to learn, since a local
            // learning pass can't hold up suggestions they're waiting for.
            if (wasShowing) MemoryRepository.get(this).learnIfDue()
            panel.remove()
        }
        capture = ChatCapture(
            screen = { AccessibilityBridge.screen.value },
            overlay = bubble,
            screenSize = {
                resources.displayMetrics.let { ScreenSize(it.widthPixels, it.heightPixels) }
            },
        )
        followThemeSetting()
        followForegroundApp()
    }

    /** "System" theme follows the device's dark mode, so re-resolve when it flips. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPalette(resolvePalette())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert the foreground notification on every (re)start — including the
        // null-intent restart the system issues after killing us — so we never
        // trip the "startForeground not called in time" ANR and the bubble is
        // re-attached by onCreate. START_STICKY asks the system to recreate us.
        startForegroundNotice()
        return START_STICKY
    }

    /**
     * Swiping the app out of recents kills the task on aggressive OEMs, taking
     * this foreground service (and the bubble) with it. Schedule a prompt restart
     * so the assistant survives being swiped away.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = Intent(applicationContext, FloatingBubbleService::class.java)
            .setPackage(packageName)
        val pi = PendingIntent.getForegroundService(
            this,
            1,
            restart,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pi)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        generating?.cancel()
        scope.cancel()
        if (::panel.isInitialized) panel.remove()
        if (::bubble.isInitialized) bubble.remove()
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
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val variantId = SettingsRepository.get(this).current().themeVariantId
        return BubblePalette.from(ThemeVariant.resolve(variantId, systemDark).colorScheme)
    }

    /** Re-colour the bubble live when the theme is changed in settings. */
    private fun followThemeSetting() {
        scope.launch {
            SettingsRepository.get(this@FloatingBubbleService).state
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
        val settings = SettingsRepository.get(this).current()
        if (!settings.useLocal && settings.apiKey.isBlank()) {
            toast(getString(R.string.svc_need_api_key))
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
            panel.showError(getString(hint))
            return
        }
        // Local-mode problems (unsupported device, model not downloaded, load failure)
        // come back from the AiClient as an AiException, shown like any other failure.
        val result = runCatching {
            suggestions.suggestFor(settings, conv, MemoryRepository.get(this@FloatingBubbleService))
        }
        // Superseded by a newer tap: don't paint an error over the new request.
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        result.onSuccess {
            panel.showSuggestions(it, conv.lastFromOther())
            DiagLog.recordDuration(TimedOp.TAP_TO_RESULT, SystemClock.elapsedRealtime() - tappedAt)
        }
            .onFailure { panel.showError(it.toUserMessage(this@FloatingBubbleService)) }
    }

    /** What's on screen this moment, via the node tree or (OCR-list apps) a screenshot. */
    private suspend fun resolveConversation(settings: AppSettings): CaptureResult =
        capture.read(AccessibilityBridge.foregroundPackage ?: "", settings.ocrPackageSet)

    private fun onSuggestionChosen(suggestion: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_suggestion", suggestion))

        panel.remove()
        val autoPaste = SettingsRepository.get(this).current().autoPaste
        if (!autoPaste) {
            toast(getString(R.string.svc_copied))
            return
        }
        scope.launch {
            val pkg = AccessibilityBridge.foregroundPackage
            val screen = AccessibilityBridge.screen.value
            val pasted = screen != null && screen.fill(suggestion, pkg)
            if (screen == null) DiagLog.record(ErrorCode.A11Y_305, pkg = pkg)
            toast(if (pasted) getString(R.string.svc_filled) else getString(R.string.svc_copied))
        }
    }

    // ---- helpers ---------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startForegroundNotice() {
        val channelId = "overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                channelId,
                getString(R.string.svc_bubble_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.svc_notif_title))
            .setContentText(getString(R.string.svc_notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(contentIntent)
            .setOngoing(true) // persistent: not swipe-dismissable while running
            .build()
        runCatching {
            // A service type is required from API 34 (where specialUse was added); below
            // that the manifest type is enough.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }.onFailure {
            // Android 12+ refuses to start a foreground service from the background
            // (ForegroundServiceStartNotAllowedException): the bubble then goes away.
            DiagLog.record(ErrorCode.BUB_501, cause = it)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
