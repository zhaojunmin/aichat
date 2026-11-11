package com.octos.aichat.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.octos.aichat.AppBus
import com.octos.aichat.ai.AiRepository
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.Conversation
import com.octos.aichat.data.SettingsRepository
import com.octos.aichat.ocr.OcrReader
import kotlinx.coroutines.CoroutineScope
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
 * reader last captured, and shows them in a panel. Tapping a candidate copies it
 * (and optionally auto-pastes into the chat input).
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bubble: View
    private var panel: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var generating: Job? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (::bubble.isInitialized) bubble.visibility = View.GONE
        removePanel()
    }

    private val dp get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppBus.bubbleService = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotice()
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(hideRunnable)
        generating?.cancel()
        scope.cancel()
        removePanel()
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
            background = circle(0xFF6C5CE7.toInt())
        }
        val lp = overlayParams(size, size).apply {
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
                    if (!dragging) v.performClick()
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { onBubbleTapped() }
    }

    private fun clamp(value: Int, max: Int): Int = value.coerceIn(0, max.coerceAtLeast(0))

    private fun onBubbleTapped() {
        if (panel != null) { removePanel(); return }
        val settings = SettingsRepository.get(this).current()
        if (settings.apiKey.isBlank()) {
            toast("请先在 App 内填写 API Key")
            return
        }
        generating?.cancel()
        generating = scope.launch {
            // Resolve the conversation first (may involve a screenshot); only then
            // show the loading panel so it isn't captured into the screenshot.
            val conv = resolveConversation(settings)
            showPanelLoading()
            if (conv == null || conv.isEmpty) {
                showPanelError(
                    if (settings.useScreenshotOcr) "截图未识别到聊天内容(需无障碍已开启)"
                    else "未读取到聊天内容,请打开聊天界面并开启无障碍服务"
                )
                return@launch
            }
            val result = runCatching { AiRepository.generateReplies(settings, conv) }
            result.onSuccess { showPanelResults(it) }
                .onFailure { showPanelError(it.message ?: "生成失败") }
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
        val bmp = svc.captureScreenshot()
        if (::bubble.isInitialized) bubble.visibility = View.VISIBLE
        bmp ?: return null

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val conv = withContext(Dispatchers.Default) {
            OcrReader.readConversation(bmp, w, h, pkg)
        }
        bmp.recycle()
        AppBus.updateConversation(conv) // let the in-app debug viewer show it too
        return conv
    }

    // ---- Panel -----------------------------------------------------------

    private fun showPanelLoading() {
        val container = basePanel()
        container.addView(header("正在生成回复…"))
        container.addView(ProgressBar(this).apply {
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
        })
        swapPanel(container)
    }

    private fun showPanelError(msg: String) {
        val container = basePanel()
        container.addView(header("生成失败"))
        container.addView(bodyText(msg))
        swapPanel(container)
    }

    private fun showPanelResults(replies: List<String>) {
        val container = basePanel()
        val other = AppBus.conversation.lastFromOther()
        container.addView(header(if (other != null) "对方: ${other.take(40)}" else "AI 回复建议"))
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (320 * dp).toInt()
            )
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        replies.forEachIndexed { i, reply ->
            list.addView(replyCard(i + 1, reply))
        }
        scroll.addView(list)
        container.addView(scroll)
        swapPanel(container)
    }

    private fun replyCard(index: Int, reply: String): View = TextView(this).apply {
        text = "$index.  $reply"
        setTextColor(0xFF1A1A1A.toInt())
        textSize = 15f
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
        background = roundedRect(0xFFF1F0FE.toInt(), 12f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() }
        isClickable = true
        setOnClickListener { onReplyChosen(reply) }
    }

    private fun onReplyChosen(reply: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ai_reply", reply))

        val autoPaste = SettingsRepository.get(this).current().autoPaste
        val pasted = autoPaste && AppBus.accessibilityService?.pasteText(reply) == true
        toast(if (pasted) "已填入输入框" else "已复制,去粘贴发送")
        removePanel()
    }

    private fun basePanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt()
        setPadding(p, p, p, p)
        background = roundedRect(Color.WHITE, 20f)
    }

    private fun header(title: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context).apply {
            text = title
            setTextColor(0xFF6C5CE7.toInt())
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(context).apply {
            text = "✕"
            setTextColor(0xFF999999.toInt())
            textSize = 18f
            val pad = (4 * dp).toInt(); setPadding(pad * 2, pad, pad * 2, pad)
            setOnClickListener { removePanel() }
        })
    }

    private fun bodyText(msg: String): View = TextView(this).apply {
        text = msg
        setTextColor(0xFF666666.toInt())
        textSize = 14f
        setPadding(0, (8 * dp).toInt(), 0, 0)
    }

    private fun swapPanel(view: View) {
        removePanel()
        val lp = overlayParams(
            (320 * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        panel = view
        wm.addView(view, lp)
    }

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    // ---- helpers ---------------------------------------------------------

    private fun overlayParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundedRect(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * dp
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startForegroundNotice() {
        val channelId = "overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "悬浮助手", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("聊天搭子运行中")
            .setContentText("点击悬浮球生成 AI 回复建议")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val HIDE_DELAY_MS = 1000L

        fun start(context: Context) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
