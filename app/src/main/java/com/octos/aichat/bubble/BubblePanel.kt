package com.octos.aichat.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.octos.aichat.R
import com.octos.aichat.diagnostics.DiagLog
import com.octos.aichat.diagnostics.ErrorCode

/**
 * The floating result panel: builds and swaps the loading / error / results views
 * and owns its single overlay window. Pure UI — all business decisions (what to
 * generate, what happens on a suggestion tap) stay in [BubbleController] and arrive
 * via [onSuggestionChosen]. Single responsibility: paint the panel.
 */
internal class BubblePanel(
    private val context: Context,
    private val wm: WindowManager,
    /** Theme colours; a change takes effect from the next show (an open panel keeps its look). */
    var palette: BubblePalette,
    private val onSuggestionChosen: (String) -> Unit,
) {

    private var view: View? = null
    private val dp get() = context.resources.displayMetrics.density

    val isShowing: Boolean get() = view != null

    fun showLoading(title: String = context.getString(R.string.bubble_generating)) {
        val c = basePanel()
        c.addView(header(title))
        c.addView(
            ProgressBar(context).apply {
                val p = (12 * dp).toInt()
                setPadding(p, p, p, p)
                indeterminateTintList = ColorStateList.valueOf(palette.accent)
            },
        )
        swap(c)
    }

    fun showError(msg: String) {
        val c = basePanel()
        c.addView(header(context.getString(R.string.generation_failed)))
        c.addView(bodyText(msg))
        swap(c)
    }

    fun showSuggestions(suggestions: List<String>, otherLine: String?) {
        val c = basePanel()
        c.addView(
            header(
                if (otherLine !=
                    null
                ) {
                    context.getString(R.string.bubble_other_preview, otherLine.take(40))
                } else {
                    context.getString(R.string.bubble_suggestions_title)
                },
            ),
        )
        // As tall as the candidates need, capped so a long list scrolls instead of
        // covering the whole chat.
        val maxListHeight = minOf(
            (320 * dp).toInt(),
            context.resources.displayMetrics.heightPixels / 2,
        )
        val scroll = MaxHeightScrollView(context, maxListHeight).apply {
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        suggestions.forEachIndexed { i, s -> list.addView(suggestionCard(i + 1, s)) }
        scroll.addView(list)
        c.addView(scroll)
        swap(c)
    }

    fun remove() {
        view?.let { v ->
            runCatching {
                wm.removeView(v)
            }.onFailure { DiagLog.record(ErrorCode.BUB_502, cause = it) }
        }
        view = null
    }

    private fun swap(v: View) {
        remove()
        val lp = BubbleStyle.params((320 * dp).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER }
        view = v
        wm.addView(v, lp)
    }

    private fun basePanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt()
        setPadding(p, p, p, p)
        background = BubbleStyle.roundedRect(palette.panel, 20f, dp)
    }

    private fun header(title: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(
            TextView(context).apply {
                text = title
                setTextColor(palette.title)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        addView(
            TextView(context).apply {
                text = "✕"
                setTextColor(palette.muted)
                textSize = 18f
                val pad = (4 * dp).toInt()
                setPadding(pad * 2, pad, pad * 2, pad)
                setOnClickListener { remove() }
            },
        )
    }

    private fun bodyText(msg: String): View = TextView(context).apply {
        text = msg
        setTextColor(palette.muted)
        textSize = 14f
        setPadding(0, (8 * dp).toInt(), 0, 0)
    }

    private fun suggestionCard(index: Int, suggestion: String): View = TextView(context).apply {
        text = context.getString(R.string.panel_suggestion_item, index, suggestion)
        setTextColor(palette.onCard)
        textSize = 15f
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
        background = BubbleStyle.roundedRect(palette.card, 12f, dp)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (8 * dp).toInt() }
        isClickable = true
        setOnClickListener { onSuggestionChosen(suggestion) }
    }
}

/**
 * A [ScrollView] that wraps its content up to [maxHeight], then scrolls. Built in code only, never
 * inflated.
 */
@SuppressLint("ViewConstructor")
private class MaxHeightScrollView(context: Context, private val maxHeight: Int) :
    ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
        )
    }
}
