package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
    private lateinit var stop: TextView
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
    private fun animateIn(view: View, delay: Long = 0L) {
        view.alpha = 0f
        view.translationY = dp(8).toFloat()
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF02080A.toInt(), 0xFF071311.toInt(), 0xFF0A1715.toInt()))
            setPadding(dp(14), dp(10), dp(14), dp(9))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF0C3028.toInt(), 0xFF0A1916.toInt(), 0xFF0B241E.toInt())).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0xFF016856.toInt())
            }
            elevation = dp(4).toFloat()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        orb = OrbView(this).apply { contentDescription = "Orion Jarvis-style animated core" }
        header.addView(orb, LinearLayout.LayoutParams(dp(62), dp(62)))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titleCol.addView(label("ORION", 18f, Theme.TEXT, true))
        titleCol.addView(label("PERSONAL AI COMPANION", 9f, Theme.MUTED, true).apply {
            letterSpacing = .08f
            setPadding(0, dp(1), 0, dp(3))
        })
        status = label("●  READY WHEN YOU ARE", 11f, Theme.CYAN, true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(0xFF082D26.toInt(), 12, 0xFF018D6D.toInt())
            contentDescription = "Orion state: ready"
        }
        titleCol.addView(status)
        titleCol.addView(label("Think clearly. Act with permission.", 11f, Theme.MUTED))
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        stop = label("■", 13f, 0xFF15EAD3.toInt(), true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Stop current Orion task"
            background = rounded(0xFF0B241E.toInt(), 14, 0xFF018D6D.toInt())
            setPadding(dp(11), dp(10), dp(11), dp(10))
            visibility = View.GONE
            setOnClickListener {
                PhoneAiService.instance?.stopCurrentTask()
                text = "…"
                isEnabled = false
                Toast.makeText(this@MainChatActivity, "Asked Orion to stop.", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(stop, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
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
        animateIn(header, 30)

        val intro = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(15), dp(17), dp(15))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0C3028.toInt(), 0xFF0A1916.toInt(), 0xFF0B241E.toInt())).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF2C504E.toInt())
            }
            elevation = dp(2).toFloat()
        }
        intro.addView(label("YOUR AI. YOUR FLOW.", 10f, Theme.CYAN, true).apply {
            letterSpacing = .13f
        })
        intro.addView(label("What’s on your mind?", 24f, Theme.TEXT, true).apply {
            setPadding(0, dp(5), 0, dp(2))
        })
        intro.addView(label("Think, create, research, or get things done — all with Orion.", 13f, Theme.MUTED).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        val introFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, 0)
        }
        introFooter.addView(OrionIconView(this).apply {
            icon = "shield"; tint = Theme.OK; contentDescription = null
        }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { rightMargin = dp(6) })
        introFooter.addView(label("Private by design · actions need your approval", 10f, Theme.MUTED))
        intro.addView(introFooter)
        root.addView(intro)
        animateIn(intro, 90)

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
            Triple("Research", "browser", "Help me research a topic. Ask what topic I want to investigate."),
            Triple("Plan", "tasks", "Help me create a practical step-by-step plan. Ask what I want to achieve."),
            Triple("Explain", "spark", "Explain a difficult idea with a practical example."),
            Triple("Phone command", "shield", "Help me with my phone. Ask what action I want to perform.")
        ).forEach { (name, iconName, prompt) ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(8), dp(13), dp(8))
                background = rounded(Theme.PANEL2, 18, Theme.LINE)
                isClickable = true
                isFocusable = true
                contentDescription = "$name prompt"
            }
            chip.addView(OrionIconView(this).apply {
                icon = iconName
                tint = Theme.CYAN
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(6) })
            chip.addView(label(name, 12f, Theme.CYAN, true))
            chip.setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.96f).scaleY(.96f).setDuration(70).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                false
            }
            chip.setOnClickListener { input.setText(prompt); input.setSelection(input.text.length); input.requestFocus() }
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
        chipsScroll.addView(chips)
        root.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF10211E.toInt(), 0xFF0A1413.toInt())).apply {
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), 0xFF2C504E.toInt())
            }
            elevation = dp(5).toFloat()
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
        val mic = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Theme.PANEL2, 17, Theme.LINE)
            setPadding(dp(11), dp(12), dp(11), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "Speak to Orion"
            addView(OrionIconView(this@MainChatActivity).apply {
                icon = "mic"; tint = Theme.CYAN; contentDescription = null
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(5) })
            addView(label("Mic", 12f, Theme.CYAN, true))
            setOnClickListener {
                val service = PhoneAiService.instance
                if (service == null) {
                    Toast.makeText(this@MainChatActivity, "Enable Orion in Accessibility settings first.", Toast.LENGTH_LONG).show()
                } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 43)
                } else {
                    service.listenFromMainChat()
                }
            }
        }
        composer.addView(mic, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
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

    override fun onStop() {
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onStop()
    }

    override fun onDestroy() {
        attachedService?.detachMainChat(this)
        attachedService = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            PhoneAiService.instance?.listenFromMainChat()
        }
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
        val isNote = kind == "note"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isNote) 12 else 15), dp(if (isNote) 9 else 12), dp(if (isNote) 12 else 15), dp(if (isNote) 9 else 12))
            background = when {
                isNote -> rounded(0xFF082D26.toInt(), 15, 0xFF018D6D.toInt())
                kind == "me" -> GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF0E4035.toInt(), 0xFF0C3028.toInt())).apply {
                    cornerRadius = dp(21).toFloat()
                    setStroke(dp(1), 0xFF016856.toInt())
                }
                else -> rounded(0xFF0A1916.toInt(), 19, 0xFF21443D.toInt())
            }
            elevation = if (isNote) 0f else dp(1).toFloat()
        }
        if (isNote) row.addView(label("LIVE ACTIVITY", 9f, Theme.CYAN, true).apply { setPadding(0, 0, 0, dp(3)) })
        else if (kind == "ai") row.addView(label("ORION", 10f, Theme.CYAN, true).apply { setPadding(0, 0, 0, dp(4)) })
        val body = label(text, if (isNote) 12f else 15f, if (isNote) Theme.MUTED else Theme.TEXT).apply {
            setLineSpacing(dp(2).toFloat(), 1.02f)
            setTextIsSelectable(true)
            if (isNote) maxLines = 4
        }
        row.addView(body)
        val params = LinearLayout.LayoutParams(
            if (isNote) -1 else if (kind == "me") (resources.displayMetrics.widthPixels * .84f).toInt() else -1, -2
        )
        params.gravity = if (kind == "me") Gravity.END else Gravity.START
        params.setMargins(if (kind == "me") dp(36) else 0, dp(if (isNote) 2 else 5), if (kind == "me") 0 else dp(if (isNote) 0 else 18), dp(if (isNote) 3 else 5))
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
            val changed = lastMode != mode
            lastMode = mode
            val state: Triple<String, Int, Pair<Int, Int>> = when (mode) {
                OrbView.Mode.IDLE -> Triple("READY", Theme.CYAN, Pair(0xFF10252D.toInt(), 0xFF245260.toInt()))
                OrbView.Mode.LISTENING -> Triple("LISTENING · SPEAK NOW", Theme.OK, Pair(0xFF063B30.toInt(), 0xFF018D6D.toInt()))
                OrbView.Mode.THINKING -> Triple("THINKING · WORKING IT OUT", Theme.VIOLET, Pair(0xFF0C3028.toInt(), 0xFF016856.toInt()))
                OrbView.Mode.SPEAKING -> Triple("SPEAKING · ORION IS REPLYING", Theme.CYAN, Pair(0xFF10252D.toInt(), 0xFF245260.toInt()))
                OrbView.Mode.ACTING -> Triple("ACTING · CARRYING OUT TASK", Theme.WARN, Pair(0xFF302515.toInt(), 0xFF705328.toInt()))
                OrbView.Mode.WAITING_APPROVAL -> Triple("APPROVAL NEEDED · YOU DECIDE", Theme.WARN, Pair(0xFF302515.toInt(), 0xFF8B672B.toInt()))
            }
            val short = state.first
            val accent = state.second
            val fill = state.third.first
            val border = state.third.second
            status.text = "●  " + short
            status.setTextColor(accent)
            status.background = rounded(fill, 12, border)
            status.contentDescription = "Orion state: " + short
            orb.contentDescription = "Orion animated core. " + short
            orb.mode = mode
            if (changed) {
                status.animate().cancel()
                status.alpha = .72f
                status.scaleX = .98f
                status.scaleY = .98f
                status.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            val busy = mode != OrbView.Mode.IDLE || PhoneAiService.instance?.isTaskRunning() == true
            stop.visibility = if (busy) View.VISIBLE else View.GONE
            stop.isEnabled = busy
            stop.text = "■"
            if (busy) {
                stop.animate().cancel()
                stop.scaleX = .94f
                stop.scaleY = .94f
                stop.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            send.alpha = if (mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING) .72f else 1f
        }
    }
}
