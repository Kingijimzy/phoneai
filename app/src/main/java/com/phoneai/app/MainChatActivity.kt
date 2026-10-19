package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Full-screen conversation. The floating orb keeps its separate, compact command panel. */
class MainChatActivity : Activity(), PhoneAiService.MainChatListener {
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: TextView
    private var attachedService: PhoneAiService? = null
    private var lastMode = OrbView.Mode.IDLE

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Theme.PANEL, 22, Theme.LINE)
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        orb = OrbView(this).apply { contentDescription = "Orion Jarvis-style animated core" }
        header.addView(orb, LinearLayout.LayoutParams(dp(62), dp(62)))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titleCol.addView(label("ORION", 17f, Theme.TEXT, true))
        status = label("PERSONAL AI  ·  READY", 11f, Theme.CYAN, true)
        titleCol.addView(status)
        titleCol.addView(label("Think clearly. Act with permission.", 11f, Theme.MUTED))
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        val new = label("＋", 24f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Start a new conversation"
            setPadding(dp(10), dp(6), dp(8), dp(6))
            setOnClickListener {
                if (lastMode == OrbView.Mode.THINKING || lastMode == OrbView.Mode.ACTING) {
                    Toast.makeText(this@MainChatActivity, "Let Orion finish or stop the current task first.", Toast.LENGTH_SHORT).show()
                } else {
                    messages.removeAllViews()
                    val service = PhoneAiService.instance
                    if (service != null) service.resetMainChat()
                    else addMessage("ai", "New conversation. What are we working on?")
                }
            }
        }
        header.addView(new)
        root.addView(header)

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(15), dp(4), dp(4))
        }
        intro.addView(label("What’s on your mind?", 23f, Theme.TEXT, true))
        intro.addView(label("Ask a question, think through a decision, or give Orion a task.", 13f, Theme.MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(intro)

        scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
        }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val chipsScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Research" to "Help me research a topic. Ask what topic I want to investigate.",
            "Plan" to "Help me create a practical step-by-step plan. Ask what I want to achieve.",
            "Explain" to "Explain a difficult idea with a practical example.",
            "Phone command" to "Help me with my phone. Ask what action I want to perform."
        ).forEach { (name, prompt) ->
            val chip = label(name, 12f, Theme.CYAN, true).apply {
                setPadding(dp(13), dp(9), dp(13), dp(9))
                background = rounded(Theme.PANEL2, 18, Theme.LINE)
                setOnClickListener { input.setText(prompt); input.setSelection(input.text.length); input.requestFocus() }
            }
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
        chipsScroll.addView(chips)
        root.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Theme.PANEL, 24, Theme.LINE)
            setPadding(dp(8), dp(6), dp(7), dp(6))
        }
        input = EditText(this).apply {
            hint = "Message Orion…"
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 15f
            background = null
            minHeight = dp(44)
            maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        send = label("↑", 22f, Theme.ON_ACCENT, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Theme.CYAN, 17)
            setPadding(dp(15), dp(8), dp(15), dp(8))
            contentDescription = "Send message"
            setOnClickListener { sendMessage() }
        }
        composer.addView(send)
        root.addView(composer)
        root.addView(label("Orion only performs sensitive actions after asking you.", 10f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(1))
        })
        setContentView(root)
        addMessage("ai", "Hi. I’m Orion. We can talk naturally here; phone-control commands are handled with a separate permission-aware workflow.")
    }

    override fun onResume() {
        super.onResume()
        val service = PhoneAiService.instance
        if (service == null) {
            status.text = "ACCESSIBILITY SETUP NEEDED"
            status.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else if (attachedService !== service) {
            attachedService?.detachMainChat(this)
            attachedService = service
            messages.removeAllViews()
            service.attachMainChat(this)
        }
    }

    override fun onDestroy() {
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onDestroy()
    }

    private fun sendMessage() {
        val value = input.text.toString().trim()
        if (value.isEmpty()) return
        val service = PhoneAiService.instance
        if (service == null) {
            Toast.makeText(this, "Enable Orion in Accessibility settings first.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        input.setText("")
        service.sendFromMainChat(value)
    }

    private fun addMessage(kind: String, text: String) {
        if (kind == "note") return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = if (kind == "me") rounded(0xFF202838.toInt(), 19, 0xFF344056.toInt()) else rounded(Theme.PANEL, 18, Theme.LINE)
        }
        if (kind == "ai") row.addView(label("ORION", 10f, Theme.CYAN, true).apply { setPadding(0, 0, 0, dp(4)) })
        val body = label(text, 15f, Theme.TEXT).apply {
            setLineSpacing(dp(2).toFloat(), 1.02f)
            textIsSelectable = true
        }
        row.addView(body)
        val params = LinearLayout.LayoutParams(if (kind == "me") (resources.displayMetrics.widthPixels * .84f).toInt() else -1, -2)
        params.gravity = if (kind == "me") Gravity.END else Gravity.START
        params.setMargins(if (kind == "me") dp(36) else 0, dp(5), if (kind == "me") 0 else dp(18), dp(5))
        messages.addView(row, params)
        row.alpha = 0f
        row.translationY = dp(8).toFloat()
        row.animate().alpha(1f).translationY(0f).setDuration(180).start()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onMessage(kind: String, text: String) {
        runOnUiThread { addMessage(kind, text) }
    }

    override fun onStatus(text: String, mode: OrbView.Mode) {
        runOnUiThread {
            lastMode = mode
            status.text = "ORION  ·  " + text.uppercase()
            orb.mode = mode
            send.alpha = if (mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING) .55f else 1f
        }
    }
}
