package com.phoneai.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Small view factory shared by every Code Studio page so they look and behave like one product. */
internal class StudioUi(val ctx: Context) {
    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun label(text: String, size: Float = 13f, color: Int = Theme.TEXT, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(3), 0, dp(3))
    }

    fun section(title: String) = label(title.uppercase(), 10f, Theme.MUTED, true).apply { letterSpacing = .12f; setPadding(0, dp(18), 0, dp(4)) }

    fun panel(fill: Int = Theme.PANEL, stroke: Int = Theme.LINE) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(14).toFloat(); setStroke(dp(1), stroke)
    }

    fun chip(caption: String, selected: Boolean = false, action: () -> Unit) = TextView(ctx).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(if (selected) Theme.BG else Theme.TEXT)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply {
            setColor(if (selected) Theme.SILVER else Theme.PANEL2); cornerRadius = dp(11).toFloat(); setStroke(dp(1), Theme.LINE_STRONG)
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        isClickable = true; setOnClickListener { action() }
    }

    fun row(vararg chips: View) = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; chips.forEach { addView(it) } })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    fun field(hint: String, multiline: Boolean = false) = EditText(ctx).apply {
        this.hint = hint; setHintTextColor(Theme.MUTED); setTextColor(Theme.TEXT); textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (!multiline) setSingleLine() else { minLines = 3; gravity = Gravity.TOP or Gravity.START }
    }

    /** Selectable monospace panel for real command output. */
    fun output(minHeightDp: Int = 140) = TextView(ctx).apply {
        typeface = Typeface.MONOSPACE; textSize = 11.5f; setTextColor(Theme.TEXT); setTextIsSelectable(true)
        setPadding(dp(12), dp(12), dp(12), dp(12)); minHeight = dp(minHeightDp); background = panel()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(12)); background = panel()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    fun badge(text: String, color: Int) = TextView(ctx).apply {
        this.text = text; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color); setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), color) }
    }
}

/** One page of the Code Studio. Pages are built once, shown on demand, and talk to the shared backend only. */
internal abstract class StudioPage(val host: StudioActivity) {
    val ui: StudioUi get() = host.ui
    abstract val id: String
    abstract val title: String
    abstract fun build(root: LinearLayout)
    open fun onShow() {}
}
