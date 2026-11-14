package com.octos.aichat.bubble

import com.octos.aichat.R

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
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.octos.aichat.AppBus
import com.octos.aichat.ui.MainActivity
import com.octos.aichat.ui.toUserMessage
import com.octos.aichat.accessibility.BubbleLocator
import com.octos.aichat.ai.AiService
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.ai.local.LlmEngine
import com.octos.aichat.ai.local.LocalModel
import com.octos.aichat.ai.local.ModelStore
import com.octos.aichat.data.memory.MemoryRepository
import com.octos.aichat.ocr.OcrReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Foreground service that paints a draggable bubble over other apps. Tapping it
 * asks the configured AI for reply candidates based on whatever the accessibility
 * reader last captured, and shows them in a panel ([BubblePanel]). Tapping a
 * candidate copies it (and optionally auto-pastes into the chat input).
 *
 * This class owns the service lifecycle, the bubble, and orchestration; the panel's
 * view construction lives in [BubblePanel] and shared drawables in [BubbleStyle].
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubble: View
    private lateinit var panel: BubblePanel
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var generating: Job? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (::bubble.isInitialized) bubble.visibility = View.GONE
        if (::panel.isInitialized) panel.remove()
    }

    private val dp get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppBus.bubbleService = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        panel = BubblePanel(this, wm, ::onReplyChosen)
        startForegroundNotice()
        addBubble()
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
            this, 1, restart,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pi)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(hideRunnable)
        generating?.cancel()
        scope.cancel()
        if (::panel.isInitialized) panel.remove()
        runCatching { if (::bubble.isInitialized) wm.removeView(bubble) }
        if (AppBus.bubbleService === this) AppBus.bubbleService = null
    }

    /**
     * Show/hide the bubble as the foreground app changes. Showing is immediate;
     * hiding is debounced so the IME, dialogs and transient sub-windows that
     * briefly steal the foreground don't make the bubble flicker — if we return
     * to a target app within [HIDE_DELAY_MS], the pending hide is cancelled.
     */
    fun setBubbleVisible(visible: Boolean) {
        if (!::bubble.isInitialized) return
        uiHandler.removeCallbacks(hideRunnable)
        if (visible) {
            if (bubble.visibility != View.VISIBLE) bubble.visibility = View.VISIBLE
        } else {
            uiHandler.postDelayed(hideRunnable, HIDE_DELAY_MS)
        }
    }

    // ---- Bubble ----------------------------------------------------------

    private var bubbleSize = 0

    private fun addBubble() {
        val size = (56 * dp).toInt()
        bubbleSize = size
        bubble = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = BubbleStyle.circle(0xFF35557A.toInt())
            // Translucent at rest so it doesn't obscure the chat underneath; it pops
            // to full opacity while touched (see attachDragAndTap).
            alpha = BUBBLE_IDLE_ALPHA
        }
        val lp = BubbleStyle.params(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * dp).toInt()
            y = (240 * dp).toInt()
        }
        attachDragAndTap(bubble, lp)
        wm.addView(bubble, lp)
    }

    private fun attachDragAndTap(view: View, lp: WindowManager.LayoutParams) {
        // Use the platform touch slop so tiny finger jitter during a tap isn't
        // misread as a drag — otherwise taps on the small target often fail to
        // open the panel.
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y; dragging = false
                    v.alpha = 1f // fully opaque while the user is interacting
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        // Clamp within the screen so the bubble can't be dragged
                        // off-screen and effectively vanish.
                        lp.x = clamp(startX + dx, resources.displayMetrics.widthPixels - bubbleSize)
                        lp.y = clamp(startY + dy, resources.displayMetrics.heightPixels - bubbleSize)
                        wm.updateViewLayout(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = BUBBLE_IDLE_ALPHA // fade back so it stays out of the way
                    if (!dragging) v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = BUBBLE_IDLE_ALPHA
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { onBubbleTapped() }
    }

    private fun clamp(value: Int, max: Int): Int = value.coerceIn(0, max.coerceAtLeast(0))

    // ---- Orchestration ---------------------------------------------------

    private fun onBubbleTapped() {
        if (panel.isShowing) { panel.remove(); return }
        val settings = SettingsRepository.get(this).current()
        if (!settings.useLocal && settings.apiKey.isBlank()) {
            toast(getString(R.string.svc_need_api_key))
            return
        }
        generating?.cancel()
        generating = scope.launch {
            // Resolve the conversation first (may involve a screenshot); only then
            // show the loading panel so it isn't captured into the screenshot.
            val conv = resolveConversation(settings)
            panel.showLoading()
            if (conv == null || conv.isEmpty) {
                panel.showError(
                    if (settings.useScreenshotOcr) getString(R.string.svc_ocr_no_content)
                    else getString(R.string.svc_no_content)
                )
                return@launch
            }
            // Local mode: make sure the on-device model is loaded before generating.
            if (settings.useLocal && !ensureLocalReady(settings.localModel)) return@launch
            // Log my on-screen replies into global memory, and let what's already
            // been learned personalise the suggestions.
            val memory = MemoryRepository.get(this@FloatingBubbleService)
            memory.recordMyMessages(conv)
            val result = runCatching { AiService.instance.generateReplies(settings, conv, memory.memoryBlock(conv)) }
            // Superseded by a newer tap: don't paint an error over the new request.
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            result.onSuccess { panel.showResults(it, conv.lastFromOther()) }
                .onFailure { panel.showError(it.toUserMessage(this@FloatingBubbleService)) }
        }
    }

    /**
     * Node tree by default. Only when OCR is enabled AND the current foreground
     * app is in the OCR list (WeChat by default) do we screenshot + OCR — so
     * Soul and others keep their precise node-tree parsing untouched.
     */
    private suspend fun resolveConversation(settings: AppSettings): Conversation? {
        val pkg = AppBus.foregroundPackage ?: ""
        // Apps in the OCR list are always read via screenshot (the node tree is
        // blocked or too noisy); the global toggle is no longer required.
        val useOcr = pkg in settings.ocrPackageSet &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (!useOcr) return AppBus.conversation

        val svc = AppBus.accessibilityService ?: return null
        // Hide our own bubble so it isn't part of the captured image.
        if (::bubble.isInitialized) bubble.visibility = View.GONE
        delay(80)
        // Grab bubble geometry from the node tree at the same moment we capture,
        // so rects and pixels line up. Empty for apps with no geometry (WeChat).
        val boxes = if (BubbleLocator.hasRule(pkg))
            BubbleLocator.locate(svc.rootInActiveWindow, resources.displayMetrics.widthPixels, pkg)
        else emptyList()
        val bmp = svc.captureScreenshot()
        if (::bubble.isInitialized) bubble.visibility = View.VISIBLE
        bmp ?: return null

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        // When the soft keyboard is up, its letters get OCR'd as chat text. Crop
        // the screenshot to just above the IME window before the whole-screen read.
        val imeTop = svc.imeBounds()?.top ?: -1
        val conv = withContext(Dispatchers.Default) {
            // Geometry-guided OCR when we have bubble rects (Momo); otherwise the
            // whole-screen colour/region heuristic (WeChat).
            when {
                boxes.isNotEmpty() -> OcrReader.readConversation(bmp, boxes, pkg)
                imeTop in 1 until bmp.height -> {
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, bmp.width, imeTop)
                    val c = OcrReader.readConversation(cropped, w, imeTop, pkg)
                    cropped.recycle()
                    c
                }
                else -> OcrReader.readConversation(bmp, w, h, pkg)
            }
        }
        bmp.recycle()
        AppBus.updateConversation(conv) // let the in-app debug viewer show it too
        return conv
    }

    /** Ensure the on-device model is loaded; shows an error panel and returns false if not. */
    private suspend fun ensureLocalReady(model: LocalModel): Boolean {
        if (!LlmEngine.isSupported) {
            panel.showError(getString(R.string.home_local_unsupported)); return false
        }
        if (!ModelStore.isReady(this, model)) {
            panel.showError(getString(R.string.svc_local_not_downloaded, model.displayName)); return false
        }
        if (!LlmEngine.isReady) panel.showLoading(getString(R.string.svc_loading_local))
        // Idempotent when the same model is already loaded; reloads on a size switch.
        if (!LlmEngine.ensureLoaded(this, model)) { panel.showError(getString(R.string.error_local_load_failed)); return false }
        return true
    }

    private fun onReplyChosen(reply: String) {
        MemoryRepository.get(this).recordReply(reply)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_reply", reply))

        val autoPaste = SettingsRepository.get(this).current().autoPaste
        val pasted = autoPaste && AppBus.accessibilityService?.pasteText(reply) == true
        toast(if (pasted) getString(R.string.svc_filled) else getString(R.string.svc_copied))
        panel.remove()
    }

    // ---- helpers ---------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startForegroundNotice() {
        val channelId = "overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.svc_bubble_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val HIDE_DELAY_MS = 1000L
        // Resting transparency of the floating ball so it doesn't block content behind it.
        private const val BUBBLE_IDLE_ALPHA = 0.45f

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
