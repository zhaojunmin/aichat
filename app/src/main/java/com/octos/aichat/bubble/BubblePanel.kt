package com.octos.aichat.bubble

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.octos.aichat.R

/**
 * The floating result panel: builds and swaps the loading / error / results views
 * and owns its single overlay window. Pure UI — all business decisions (what to
 * generate, what happens on a reply tap) stay in [FloatingBubbleService] and arrive
 * via [onReplyChosen]. Single responsibility: paint the panel.
 */
internal class BubblePanel(
    private val context: Context,
    private val wm: WindowManager,
    private val onReplyChosen: (String) -> Unit,
) {

    private var view: View? = null
    private val dp get() = context.resources.displayMetrics.density

    val isShowing: Boolean get() = view != null

    fun showLoading(title: String = context.getString(R.string.bubble_generating)) {
        val c = basePanel()
        c.addView(header(title))
        c.addView(ProgressBar(context).apply { val p = (12 * dp).toInt(); setPadding(p, p, p, p) })
        swap(c)
    }

    fun showError(msg: String) {
        val c = basePanel()
        c.addView(header(context.getString(R.string.generation_failed)))
        c.addView(bodyText(msg))
        swap(c)
    }

    fun showResults(replies: List<String>, otherLine: String?) {
        val c = basePanel()
        c.addView(header(
            if (otherLine != null) context.getString(R.string.bubble_other_preview, otherLine.take(40))
            else context.getString(R.string.bubble_suggestions_title)
        ))
        val scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (320 * dp).toInt())
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        replies.forEachIndexed { i, reply -> list.addView(replyCard(i + 1, reply)) }
        scroll.addView(list)
        c.addView(scroll)
        swap(c)
    }

    fun remove() {
        view?.let { runCatching { wm.removeView(it) } }
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
        background = BubbleStyle.roundedRect(Color.WHITE, 20f, dp)
    }

    private fun header(title: String): View = LinearLayout(context).apply {
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
            setOnClickListener { remove() }
        })
    }

    private fun bodyText(msg: String): View = TextView(context).apply {
        text = msg
        setTextColor(0xFF666666.toInt())
        textSize = 14f
        setPadding(0, (8 * dp).toInt(), 0, 0)
    }

    private fun replyCard(index: Int, reply: String): View = TextView(context).apply {
        text = "$index.  $reply"
        setTextColor(0xFF1A1A1A.toInt())
        textSize = 15f
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
        background = BubbleStyle.roundedRect(0xFFF1F0FE.toInt(), 12f, dp)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * dp).toInt() }
        isClickable = true
        setOnClickListener { onReplyChosen(reply) }
    }
}
