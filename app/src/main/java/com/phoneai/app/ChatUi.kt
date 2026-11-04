package com.phoneai.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A round button with a drawn icon, so the composer does not depend on text labels. */
internal class IconButtonView(context: Context, glyph: Glyph, private val filled: Boolean, private val ghost: Boolean = false) : View(context) {
    enum class Glyph { PLUS, SEND, MIC, MORE }

    var glyph: Glyph = glyph
        set(v) {
            if (field != v) {
                field = v
                if (Motion.enabled(context)) {
                    animate().cancel(); scaleX = 0.72f; scaleY = 0.72f
                    animate().scaleX(1f).scaleY(1f).setDuration(Theme.MOTION_MEDIUM_MS.toLong()).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                }
                invalidate()
            }
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()

    init { isClickable = true; Motion.pressFeedback(this) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2f; val cy = h / 2f
        val s = minOf(w, h) * 0.2f
        bg.color = if (filled) Theme.CYAN else Theme.PANEL2
        if (!ghost) c.drawCircle(cx, cy, minOf(w, h) / 2f, bg)
        ink.color = if (filled) Theme.ON_ACCENT else Theme.TEXT
        if (!isEnabled) ink.alpha = 90
        ink.strokeWidth = minOf(w, h) * 0.055f
        ink.style = Paint.Style.STROKE
        when (glyph) {
            Glyph.PLUS -> { c.drawLine(cx - s, cy, cx + s, cy, ink); c.drawLine(cx, cy - s, cx, cy + s, ink) }
            Glyph.SEND -> {
                c.drawLine(cx, cy + s, cx, cy - s, ink)
                c.drawLine(cx - s * 0.85f, cy - s * 0.15f, cx, cy - s, ink); c.drawLine(cx + s * 0.85f, cy - s * 0.15f, cx, cy - s, ink)
            }
            Glyph.MIC -> {
                rect.set(cx - s * 0.5f, cy - s * 1.15f, cx + s * 0.5f, cy + s * 0.35f); c.drawRoundRect(rect, s * 0.5f, s * 0.5f, ink)
                rect.set(cx - s * 0.95f, cy - s * 0.55f, cx + s * 0.95f, cy + s * 0.85f); c.drawArc(rect, 20f, 140f, false, ink)
                c.drawLine(cx, cy + s * 0.85f, cx, cy + s * 1.2f, ink)
            }
            Glyph.MORE -> { ink.style = Paint.Style.FILL; for (i in -1..1) c.drawCircle(cx, cy + i * s * 0.75f, s * 0.16f, ink) }
        }
    }
}

/** Bottom composer: add (+) on the left, a rounded field, and one round button that is the mic when empty and send when there is text. */
internal class ComposerView(context: Context, acceptsImages: Boolean, hint: String) : LinearLayout(context) {
    val input = EditText(context)
    private val attach = IconButtonView(context, IconButtonView.Glyph.PLUS, false)
    private val action = IconButtonView(context, IconButtonView.Glyph.MIC, true)
    var onSend: () -> Unit = {}
    var onMic: () -> Unit = {}
    var onAttach: () -> Unit = {}
    var hasAttachment = false
        set(v) { field = v; refresh() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL; gravity = Gravity.BOTTOM
        setPadding(dp(10), dp(8), dp(10), dp(12))
        setBackgroundColor(Theme.BG)
        if (acceptsImages) {
            addView(attach, LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) })
            attach.contentDescription = "Add a chart image"
            attach.setOnClickListener { onAttach() }
        }
        input.apply {
            this.hint = hint
            setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5; minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL
            val shape = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(22).toFloat(); setStroke(dp(1), Theme.LINE) }
            background = shape
            setOnFocusChangeListener { _, f -> shape.setStroke(dp(if (f) 2 else 1), if (f) Theme.SILVER else Theme.LINE) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { refresh() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(action, LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        action.setOnClickListener { if (action.glyph == IconButtonView.Glyph.SEND) onSend() else onMic() }
        refresh()
    }

    private fun refresh() {
        val send = input.text.isNotBlank() || hasAttachment
        action.glyph = if (send) IconButtonView.Glyph.SEND else IconButtonView.Glyph.MIC
        action.contentDescription = if (send) "Send" else "Speak"
    }
}

/** Builds the different kinds of message cards used in the trading chat. */
internal object MessageCards {
    class Row(val mark: String?, val markColor: Int, val name: String, val detail: String)

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** A card with a coloured edge and a label. Returns the card and its body text view. */
    fun typed(c: Context, kind: ChatKinds.Kind, text: CharSequence): Pair<LinearLayout, TextView> {
        val body = TextView(c).apply {
            this.text = text; textSize = 14.5f; setTextColor(Theme.TEXT); setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance(); setLineSpacing(0f, 1.08f)
        }
        val card = shell(c, kind, body)
        return card to body
    }

    /** A card made of rows, each with an optional coloured mark: used for the setup check and the capability list. */
    fun rows(c: Context, kind: ChatKinds.Kind, title: String, rows: List<Row>): LinearLayout {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(c).apply { text = title; textSize = 15f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(c, 4)) })
        rows.forEach { r ->
            val line = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(c, 6), 0, 0) }
            if (r.mark != null) line.addView(TextView(c).apply { text = r.mark; textSize = 14f; setTextColor(r.markColor); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(c, 22), -2))
            val txt = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(c).apply { text = r.name; textSize = 13.5f; setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD })
            txt.addView(TextView(c).apply { text = r.detail; textSize = 12f; setTextColor(Theme.MUTED) })
            line.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
            col.addView(line)
        }
        return shell(c, kind, col)
    }

    private fun shell(c: Context, kind: ChatKinds.Kind, content: View): LinearLayout {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(c, 16).toFloat(); setStroke(dp(c, 1), Theme.LINE) }
            clipToOutline = true
        }
        card.addView(View(c).apply { setBackgroundColor(kind.color) }, LinearLayout.LayoutParams(dp(c, 4), -1))
        val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 12)) }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(c, 6)) }
        head.addView(TextView(c).apply { text = kind.label; textSize = 10.5f; setTextColor(kind.color); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(c).apply { text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); textSize = 10f; setTextColor(Theme.MUTED) })
        inner.addView(head); inner.addView(content)
        card.addView(inner, LinearLayout.LayoutParams(0, -2, 1f))
        return card
    }
}


/** Dark dropdown used by the group switch and the overflow menu, with a fade and slide in. */
internal object DropMenu {
    class Item(val label: String, val hint: String? = null, val selected: Boolean = false)

    fun show(anchor: View, items: List<Item>, onPick: (Int) -> Unit) {
        val c = anchor.context
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6))
            background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Theme.LINE_STRONG) }
        }
        val pw = android.widget.PopupWindow(col, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        pw.elevation = dp(10).toFloat()
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        items.forEachIndexed { i, it ->
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(18), 0); minimumHeight = dp(46); minimumWidth = dp(190) }
            row.addView(TextView(c).apply { text = if (it.selected) "\u2713" else ""; textSize = 14f; setTextColor(Theme.SILVER); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(22), -2))
            val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(8), 0, dp(8)) }
            t.addView(TextView(c).apply { text = it.label; textSize = 15f; setTextColor(Theme.TEXT) })
            if (it.hint != null) t.addView(TextView(c).apply { text = it.hint; textSize = 11.5f; setTextColor(Theme.MUTED) })
            row.addView(t, LinearLayout.LayoutParams(-2, -2))
            row.isClickable = true
            Motion.pressFeedback(row)
            row.setOnClickListener { pw.dismiss(); onPick(i) }
            col.addView(row)
        }
        pw.showAsDropDown(anchor, 0, dp(6))
        if (Motion.enabled(c)) {
            col.alpha = 0f; col.translationY = -dp(8).toFloat(); col.scaleY = 0.96f
            col.animate().alpha(1f).translationY(0f).scaleY(1f).setDuration(Theme.MOTION_FAST_MS.toLong()).start()
        }
    }
}

/** A calm "thinking" indicator: the word followed by dots that cycle until the reply replaces it. */
internal object Thinking {
    fun start(v: TextView, word: String = "Thinking") {
        v.setTag(R_TAG, true)
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var n = 0
        val r = object : Runnable {
            override fun run() {
                if (v.getTag(R_TAG) != true || !v.isAttachedToWindow) return
                v.text = word + ".".repeat(n % 4); n++
                h.postDelayed(this, 340L)
            }
        }
        h.post(r)
    }
    fun stop(v: TextView) { v.setTag(R_TAG, false) }
    private const val R_TAG = 0x7f0b0001
}
