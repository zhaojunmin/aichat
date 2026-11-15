package com.octos.aichat.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.isVisible
import com.octos.aichat.R
import com.octos.aichat.capture.CaptureOverlay
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * The floating ball: a small draggable view in its own overlay window. Owns the view,
 * drag and tap handling, its colours and its visibility — including the debounced hide
 * when the user leaves a target app, and hiding itself while the screen is captured.
 *
 * What a tap or a hide *means* is the caller's: [onTap], [onHidden].
 */
internal class FloatingBubble(
    private val context: Context,
    private val wm: WindowManager,
    palette: BubblePalette,
    private val onTap: () -> Unit,
    /** After a debounced hide has run; [wasShowing] is false if it was already hidden. */
    private val onHidden: (wasShowing: Boolean) -> Unit,
) : CaptureOverlay {

    private val dp = context.resources.displayMetrics.density
    private val size = (56 * dp).toInt()
    private val handler = Handler(Looper.getMainLooper())

    private val view = TextView(context).apply {
        text = context.getString(R.string.bubble_label)
        textSize = 16f
        gravity = Gravity.CENTER
        // Translucent at rest so it doesn't obscure the chat underneath; it pops
        // to full opacity while touched (see attachDragAndTap).
        alpha = IDLE_ALPHA
    }

    private val lp = BubbleStyle.params(size, size).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (16 * dp).toInt()
        y = (240 * dp).toInt()
    }

    private val hideRunnable = Runnable {
        val wasShowing = view.isVisible
        view.visibility = View.GONE
        onHidden(wasShowing)
    }

    var palette: BubblePalette = palette
        set(value) {
            field = value
            paint(value)
        }

    init {
        paint(palette)
        attachDragAndTap()
        wm.addView(view, lp)
    }

    /**
     * Follow the foreground app. Showing is immediate; hiding is debounced so the IME,
     * dialogs and transient sub-windows that briefly steal the foreground don't make the
     * ball flicker — returning to a target app within [HIDE_DELAY_MS] cancels the hide.
     */
    fun setVisible(visible: Boolean) {
        handler.removeCallbacks(hideRunnable)
        if (visible) {
            if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        } else {
            handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
        }
    }

    /** Hide so the ball isn't part of the captured image; always bring it back, even on cancel. */
    override suspend fun <T> hiddenDuring(block: suspend () -> T): T {
        view.visibility = View.GONE
        try {
            delay(HIDE_SETTLE_MS) // let the hide reach the screen first
            return block()
        } finally {
            view.visibility = View.VISIBLE
        }
    }

    fun remove() {
        handler.removeCallbacks(hideRunnable)
        runCatching { wm.removeView(view) }
            .onFailure { DiagLog.record(ErrorCode.BUB_502, cause = it) }
    }

    private fun paint(p: BubblePalette) {
        view.setTextColor(p.onBall)
        view.background = BubbleStyle.circle(p.ball)
    }

    // A tap still goes through performClick (on ACTION_UP when not dragging), so the
    // accessibility click action works; lint can't see that inside the listener.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap() {
        // Use the platform touch slop so tiny finger jitter during a tap isn't
        // misread as a drag — otherwise taps on the small target often fail to
        // open the panel.
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    v.alpha = 1f // fully opaque while the user is interacting
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        // Clamp within the screen so the ball can't be dragged
                        // off-screen and effectively vanish.
                        val metrics = context.resources.displayMetrics
                        lp.x = clamp(startX + dx, metrics.widthPixels - size)
                        lp.y = clamp(startY + dy, metrics.heightPixels - size)
                        wm.updateViewLayout(view, lp)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    v.alpha = IDLE_ALPHA // fade back so it stays out of the way
                    if (!dragging) v.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = IDLE_ALPHA
                    true
                }

                else -> false
            }
        }
        view.setOnClickListener { onTap() }
    }

    private fun clamp(value: Int, max: Int): Int = value.coerceIn(0, max.coerceAtLeast(0))

    private companion object {
        const val HIDE_DELAY_MS = 1000L
        const val HIDE_SETTLE_MS = 80L

        // Resting transparency, so the ball doesn't block content behind it.
        const val IDLE_ALPHA = 0.45f
    }
}
