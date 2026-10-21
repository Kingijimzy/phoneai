package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.setContent

/** Full-screen conversation. The floating orb keeps its separate, compact command panel. */
class MainChatActivity : Activity(), PhoneAiService.MainChatListener {
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: TextView
    private lateinit var stop: TextView
    private var selectedImageUri: Uri? = null
    private var imagePreviewPanel: LinearLayout? = null
    private var commandMode = false
    private lateinit var talkModeChip: LinearLayout
    private lateinit var commandModeChip: LinearLayout
    private lateinit var talkModeIcon: OrionIconView
    private lateinit var commandModeIcon: OrionIconView
    private lateinit var talkModeText: TextView
    private lateinit var commandModeText: TextView
    private lateinit var modeHint: TextView
    private var attachedService: PhoneAiService? = null
    private var lastMode = OrbView.Mode.IDLE
    private var welcomePanel: View? = null
    private var promptRail: View? = null
    private var livePulse: android.animation.AnimatorSet? = null
    private val composeLines = mutableStateListOf<OrionChatLine>()
    private val composeStatus = mutableStateOf("READY")
    private val composeCommandMode = mutableStateOf(false)
    private val composeSelectedImage = mutableStateOf<Uri?>(null)
    private var composeChatActive = false

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
    /** Lightweight glass treatment: translucent-looking layered charcoal, a cool top sheen and a fine reflective edge. */
    private fun glass(radius: Int, stroke: Int = 0x553D6963) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xF0192527.toInt(), 0xEA10191B.toInt(), 0xF0070D0F.toInt())
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun animateIn(view: View, delay: Long = 0L) {
        view.alpha = 0f
        view.translationY = dp(8).toFloat()
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun refreshModeSelector() {
        if (!::talkModeChip.isInitialized || !::commandModeChip.isInitialized) return
        val talkSelected = !commandMode
        talkModeChip.background = rounded(if (talkSelected) 0xFF18312D.toInt() else 0xFF101719.toInt(),
            16, if (talkSelected) 0xFF3D8978.toInt() else 0xFF293638.toInt())
        commandModeChip.background = rounded(if (commandMode) 0xFF18312D.toInt() else 0xFF101719.toInt(),
            16, if (commandMode) 0xFF3D8978.toInt() else 0xFF293638.toInt())
        talkModeIcon.tint = if (talkSelected) Theme.CYAN else Theme.MUTED
        commandModeIcon.tint = if (commandMode) Theme.CYAN else Theme.MUTED
        talkModeText.setTextColor(if (talkSelected) Theme.CYAN else Theme.MUTED)
        commandModeText.setTextColor(if (commandMode) Theme.CYAN else Theme.MUTED)
        if (::input.isInitialized) input.hint = if (commandMode) "Tell Orion what to do…" else "Talk to Orion…"
        if (::modeHint.isInitialized) {
            modeHint.text = if (commandMode) {
                "Phone actions and deliberate web research · sensitive actions ask first"
            } else {
                "Normal conversation · no automatic web search or phone actions"
            }
            modeHint.setTextColor(if (commandMode) Theme.CYAN else Theme.MUTED)
        }
    }

    private fun refreshPersonalityLabel(view: TextView) {
        val profile = CharacterProfile.get(Prefs.characterProfile(this))
        view.text = profile.label.substringBefore(" — ").uppercase()
        view.background = glass(12, 0x443D6963)
        view.setPadding(dp(8), dp(5), dp(8), dp(5))
        view.setTextColor(Theme.MUTED)
    }

    private fun showPersonalityPicker() {
        val profiles = CharacterProfile.all
        val descriptions = mapOf(
            "orion" to "Calm, sharp and quietly witty. A composed everyday co-pilot.",
            "nova" to "Warm, attentive and encouraging. A thoughtful companion.",
            "atlas" to "Direct, decisive and practical. Focuses on getting things done.",
            "lumen" to "Patient and clear. Makes difficult ideas easier to understand.",
            "cipher" to "Evidence-first analysis, alternatives, uncertainty and trade-offs.",
            "muse" to "Imaginative concepts, unexpected connections and creative options.",
            "tech" to "Technical specialist for software, Android, debugging, architecture and APIs."
        )
        val iconNames = mapOf("orion" to "spark", "nova" to "voice", "atlas" to "arrow", "lumen" to "memory", "cipher" to "shield", "muse" to "skills", "tech" to "tools")
        val dialogRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF21A2325.toInt(), 0xF0091012.toInt())).apply { setStroke(dp(1), 0x553D6963); cornerRadius = dp(22).toFloat() }
        }
        val eyebrow = label("ORION PERSONALITY SYSTEM", 10f, 0xFF48DDBB.toInt(), true).apply {
            letterSpacing = .13f
            setPadding(0, dp(4), 0, dp(7))
        }
        dialogRoot.addView(eyebrow)
        dialogRoot.addView(label("Choose your mode", 23f, 0xFFF0FFFA.toInt(), true).apply {
            setPadding(0, 0, 0, dp(4))
        })
        dialogRoot.addView(label("Same Orion. Different way of thinking and communicating.", 12f, 0xFF9AB8B0.toInt()).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })
        val currentLabel = label("", 10f, 0xFF65F4D0.toInt(), true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = glass(10, 0x443D6963)
        }
        currentLabel.text = "ACTIVE MODE  ·  ${CharacterProfile.get(Prefs.characterProfile(this)).label.substringBefore(" — ").uppercase()}"
        dialogRoot.addView(currentLabel, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rowViews = mutableMapOf<String, LinearLayout>()
        val iconViews = mutableMapOf<String, OrionIconView>()
        val checkViews = mutableMapOf<String, OrionIconView>()
        val nameViews = mutableMapOf<String, TextView>()
        val descViews = mutableMapOf<String, TextView>()
        var dialog: AlertDialog? = null

        fun paintSelection(selectedId: String) {
            profiles.forEach { profile ->
                val selected = profile.id == selectedId
                rowViews[profile.id]?.apply {
                    background = rounded(if (selected) 0xFF20292B.toInt() else 0xFF111719.toInt(),
                        16, if (selected) 0xFF52716D.toInt() else 0xFF293638.toInt())
                    alpha = if (selected) 1f else .94f
                }
                iconViews[profile.id]?.apply {
                    background = rounded(if (selected) 0xFF273536.toInt() else 0xFF171F21.toInt(), 12,
                        if (selected) 0xFF52716D.toInt() else 0xFF293638.toInt())
                    tint = if (selected) Theme.CYAN else Theme.MUTED
                }
                checkViews[profile.id]?.apply {
                    icon = if (selected) "check" else "spark"
                    alpha = if (selected) 1f else 0f
                    tint = 0xFF9CE9D7.toInt()
                    background = if (selected) rounded(0xFF20312F.toInt(), 20, 0xFF3C625B.toInt()) else null
                }
                nameViews[profile.id]?.setTextColor(if (selected) 0xFFE8FFF8.toInt() else 0xFFD0E1DC.toInt())
                descViews[profile.id]?.setTextColor(if (selected) 0xFFB5E8D9.toInt() else 0xFF88A59C.toInt())
            }
        }

        profiles.forEach { profile ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(9), dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = "${profile.label}, ${descriptions[profile.id]}"
            }
            val iconView = OrionIconView(this).apply {
                this.icon = iconNames[profile.id] ?: "spark"
                tint = 0xFF9AB8B0.toInt()
                contentDescription = null
            }
            row.addView(iconView, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(11) })
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val name = label(profile.label.substringBefore(" — "), 14f, 0xFFD0E1DC.toInt(), true)
            val desc = label(descriptions[profile.id].orEmpty(), 11f, 0xFF88A59C.toInt()).apply {
                setLineSpacing(dp(1).toFloat(), 1f)
                setPadding(0, dp(3), dp(2), 0)
            }
            textCol.addView(name)
            textCol.addView(desc)
            row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            val check = OrionIconView(this).apply {
                icon = "check"
                tint = 0xFF9CE9D7.toInt()
                alpha = 0f
                background = null
                contentDescription = "Selected personality"
            }
            row.addView(check, LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(5) })
            rowViews[profile.id] = row
            iconViews[profile.id] = iconView
            checkViews[profile.id] = check
            nameViews[profile.id] = name
            descViews[profile.id] = desc
            row.setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.985f).scaleY(.985f).setDuration(65).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                false
            }
            row.setOnClickListener {
                Prefs.setCharacterProfile(this, profile.id)
                paintSelection(profile.id)
                currentLabel.text = "ACTIVE MODE  ·  ${profile.label.substringBefore(" — ").uppercase()}"
                val root = window.decorView
                fun update(view: View) {
                    if (view is TextView && view.contentDescription == "Change Orion personality") refreshPersonalityLabel(view)
                    if (view is ViewGroup) for (i in 0 until view.childCount) update(view.getChildAt(i))
                }
                update(root)
                Toast.makeText(this, "Personality set to ${profile.label.substringBefore(" — ")}", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        // Keep the complete selector reachable on small screens: the cards must scroll
        // inside a bounded viewport rather than being clipped by the dialog window.
        val listScroll = android.widget.ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, 0, 0, dp(2))
        }
        listScroll.addView(list, -1, -2)
        val availableHeight = (resources.displayMetrics.heightPixels - dp(250)).coerceAtLeast(dp(250))
        dialogRoot.addView(listScroll, LinearLayout.LayoutParams(-1, availableHeight.coerceAtMost(dp(480))))
        dialogRoot.addView(label("PERSONALITY IS SAVED ON THIS PHONE", 9f, 0xFF64877D.toInt(), true).apply {
            letterSpacing = .08f
            gravity = Gravity.CENTER
            setPadding(0, dp(7), 0, dp(2))
        })
        paintSelection(Prefs.characterProfile(this))
        dialog = AlertDialog.Builder(this)
            .setView(dialogRoot)
            .setNegativeButton("CLOSE", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(14), dp(10), dp(14), dp(9))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(5))
        }
        val mark = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = null
            contentDescription = "Orion AI"
        }
        mark.addView(OrionIconView(this).apply {
            icon = "spark"; tint = Theme.CYAN; contentDescription = null
        }, LinearLayout.LayoutParams(dp(23), dp(23)))
        header.addView(mark, LinearLayout.LayoutParams(dp(30), dp(34)))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titleCol.addView(label("Orion", 19f, Theme.TEXT, true))
        titleCol.addView(label("AI ASSISTANT", 9f, Theme.MUTED, true).apply {
            letterSpacing = .08f
            setPadding(0, dp(1), 0, dp(2))
        })
        status = label("Ready", 11f, Theme.MUTED, false).apply {
            setPadding(0, dp(3), 0, dp(1))
            contentDescription = "Orion state: ready"
        }
        titleCol.addView(status)
        val personality = label("", 9f, Theme.CYAN, true).apply {
            setPadding(0, dp(5), 0, dp(1))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            isFocusable = true
            contentDescription = "Change Orion personality"
            setOnClickListener { showPersonalityPicker() }
        }
        titleCol.addView(personality)
        refreshPersonalityLabel(personality)
        header.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        stop = label("■", 13f, Theme.CYAN, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Stop current Orion task"
            background = rounded(0xFF141F1D.toInt(), 14, 0xFF4B897C.toInt())
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
        val new = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            contentDescription = "Start a new conversation"
            isClickable = true
            isFocusable = true
            setPadding(dp(9), dp(8), dp(9), dp(8))
            addView(OrionIconView(this@MainChatActivity).apply {
                icon = "plus"; tint = Theme.MUTED; contentDescription = null
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            setOnClickListener {
                if (lastMode == OrbView.Mode.THINKING || lastMode == OrbView.Mode.ACTING) {
                    Toast.makeText(this@MainChatActivity, "Let Orion finish or stop the current task first.", Toast.LENGTH_SHORT).show()
                } else {
                    commandMode = false
                    refreshModeSelector()
                    messages.removeAllViews()
                    restoreWelcome()
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
            setPadding(dp(3), dp(3), dp(3), dp(8))
            background = null
        }
        // The reference uses a single expressive animated centerpiece; keep it compact
        // so the conversation and composer remain the focus on a 720px-wide phone.
        intro.addView(OrbView(this).apply {
            contentDescription = "Animated Orion energy core"
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(104), dp(84)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(1)
        })
        intro.addView(label("ORION", 10f, Theme.MUTED, true).apply {
            letterSpacing = .18f
            gravity = Gravity.CENTER
        })
        intro.addView(label("What can I help with?", 23f, Theme.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(2))
        })
        intro.addView(label("Ask anything, share an image, or tell me what you need done.", 13f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        val introFooter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, 0)
        }
        introFooter.addView(label("Voice · Vision · Phone actions", 10f, Theme.MUTED))
        intro.addView(introFooter)
        root.addView(intro)
        welcomePanel = intro
        animateIn(intro, 90)

        scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
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
            Triple("Phone command", "shield", "Help me open an app or perform a phone action. Ask which app or action I want."),
            Triple("Browser", "browser", ""),
            Triple("Teach mode", "record", ""),
            Triple("Tasks", "tasks", "")
        ).forEach { (name, iconName, prompt) ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(8), dp(13), dp(8))
                background = glass(16, 0x442C504E)
                isClickable = true
                isFocusable = true
                contentDescription = "$name prompt"
            }
            chip.addView(label(name, 12f, Theme.TEXT, false))
            chip.setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.96f).scaleY(.96f).setDuration(70).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                false
            }
            chip.setOnClickListener {
                when (name) {
                    "Browser" -> startActivity(Intent(this@MainChatActivity, BrowserActivity::class.java))
                    "Teach mode" -> startActivity(Intent(this@MainChatActivity, TeachModeActivity::class.java))
                    "Tasks" -> startActivity(Intent(this@MainChatActivity, TaskCenterActivity::class.java))
                    else -> {
                        commandMode = name == "Research" || name == "Phone command"
                        refreshModeSelector()
                        input.setText(prompt)
                        input.setSelection(input.text.length)
                        input.requestFocus()
                    }
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
        chipsScroll.addView(chips)
        root.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })
        promptRail = chipsScroll

        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = glass(16, 0x553D6963)
        }
        imagePreviewPanel = previewPanel
        root.addView(previewPanel, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        })

        // Clear intent selector: Talk is tool-free by default; Command is the deliberate
        // Jarvis action mode. This prevents ordinary conversation from accidentally searching
        // the web or triggering device actions.
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(7), dp(2), dp(5))
        }
        modeRow.addView(label("MODE", 9f, Theme.MUTED, true).apply {
            letterSpacing = .12f
            setPadding(0, 0, dp(8), 0)
        })
        fun makeModeChip(title: String, iconName: String): Triple<LinearLayout, OrionIconView, TextView> {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(8), dp(11), dp(8))
                isClickable = true
                isFocusable = true
            }
            val glyph = OrionIconView(this).apply {
                icon = iconName
                tint = Theme.MUTED
                contentDescription = null
            }
            chip.addView(glyph, LinearLayout.LayoutParams(dp(17), dp(17)).apply { rightMargin = dp(6) })
            val caption = label(title, 11f, Theme.MUTED, true).apply { maxLines = 1 }
            chip.addView(caption)
            return Triple(chip, glyph, caption)
        }
        val talkParts = makeModeChip("Talk", "chat")
        talkModeChip = talkParts.first
        talkModeIcon = talkParts.second
        talkModeText = talkParts.third
        talkModeChip.contentDescription = "Talk mode. Conversational answers without phone actions or automatic web search."
        talkModeChip.setOnClickListener { commandMode = false; refreshModeSelector() }
        modeRow.addView(talkModeChip, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        val commandParts = makeModeChip("Command", "spark")
        commandModeChip = commandParts.first
        commandModeIcon = commandParts.second
        commandModeText = commandParts.third
        commandModeChip.contentDescription = "Command mode. Ask Orion to use phone tools or search when requested."
        commandModeChip.setOnClickListener { commandMode = true; refreshModeSelector() }
        modeRow.addView(commandModeChip, LinearLayout.LayoutParams(0, -2, 1.25f))
        root.addView(modeRow)
        modeHint = label("Normal conversation · no automatic web search or phone actions", 10f, Theme.MUTED).apply {
            setPadding(dp(4), 0, dp(4), dp(4))
            maxLines = 2
            setLineSpacing(dp(1).toFloat(), 1f)
            contentDescription = "Explanation of the selected Orion mode"
        }
        root.addView(modeHint, LinearLayout.LayoutParams(-1, -2))
        refreshModeSelector()

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(24, 0x66516E6B)
            elevation = dp(3).toFloat()
            setPadding(dp(7), dp(5), dp(6), dp(5))
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
            // When the keyboard opens, let the conversation take the available height.
            // Restore the welcome rail with the New Chat control.
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    welcomePanel?.visibility = View.GONE
                    promptRail?.visibility = View.GONE
                }
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val attach = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glass(20, 0x443D6963)
            isClickable = true
            isFocusable = true
            contentDescription = "Attach an image for Orion to analyse"
            addView(OrionIconView(this@MainChatActivity).apply {
                icon = "image"
                tint = Theme.CYAN
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.92f).scaleY(.92f).setDuration(75).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                false
            }
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(picker, "Choose an image"), 44)
            }
        }
        composer.addView(attach, LinearLayout.LayoutParams(dp(42), dp(44)).apply { rightMargin = dp(6) })
        send = label("↑", 23f, 0xFF06110F.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF9FFFE5.toInt(), 0xFF00CFA1.toInt())).apply { cornerRadius = dp(23).toFloat(); setStroke(dp(1), 0xFFB8FFEC.toInt()) }
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            contentDescription = "Send message"
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.90f).scaleY(.90f).translationY(dp(1).toFloat()).setDuration(70).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(145).start()
                false
            }
            setOnClickListener { sendMessage() }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(composer)
        root.addView(label("Orion only performs sensitive actions after asking you.", 10f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(1))
        })
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(6), dp(7), dp(5))
            background = glass(23, 0x444C6462)
            elevation = dp(2).toFloat()
        }
        fun dockItem(title: String, iconName: String, selected: Boolean = false, action: () -> Unit): View {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = title
                setPadding(dp(3), dp(3), dp(3), dp(2))
                background = if (selected) rounded(0xFF172124.toInt(), 14, Theme.LINE) else null
            }
            item.addView(OrionIconView(this).apply { icon = iconName; tint = if (selected) Theme.CYAN else Theme.MUTED; contentDescription = null }, LinearLayout.LayoutParams(dp(21), dp(21)))
            item.addView(label(title, 9f, if (selected) Theme.CYAN else Theme.MUTED, selected).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) })
            item.setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.94f).scaleY(.94f).setDuration(70).start()
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                false
            }
            item.setOnClickListener { action() }
            return item
        }
        dock.addView(dockItem("Chat", "chat", true) { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        dock.addView(dockItem("Browser", "browser") { startActivity(Intent(this@MainChatActivity, BrowserActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        val liveDock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = "Start full-screen live voice call with Orion"; setPadding(dp(4), 0, dp(4), 0) }
        val liveButton = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFB5F5E5.toInt(), 0xFF66DCC0.toInt())).apply { shape = GradientDrawable.OVAL; setStroke(dp(1), 0xFFE0FFF6.toInt()) }
            elevation = dp(1).toFloat()
            addView(OrionIconView(this@MainChatActivity).apply { icon = "call"; tint = 0xFF02110D.toInt(); contentDescription = null }, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        liveDock.addView(liveButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        val pulseX = android.animation.ObjectAnimator.ofFloat(liveButton, View.SCALE_X, 1f, 1.045f, 1f).apply {
            duration = 1900L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }
        val pulseY = android.animation.ObjectAnimator.ofFloat(liveButton, View.SCALE_Y, 1f, 1.045f, 1f).apply {
            duration = 1900L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
        }
        livePulse = android.animation.AnimatorSet().apply {
            playTogether(pulseX, pulseY)
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        liveDock.addView(label("Live", 10f, Theme.MUTED, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
        liveDock.setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(.94f).scaleY(.94f).setDuration(70).start()
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            false
        }
        liveDock.setOnClickListener { startActivity(Intent(this@MainChatActivity, LiveConversationActivity::class.java)) }
        dock.addView(liveDock, LinearLayout.LayoutParams(0, dp(61), 1.15f))
        dock.addView(dockItem("Teach", "record") { startActivity(Intent(this@MainChatActivity, TeachModeActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        dock.addView(dockItem("Tasks", "tasks") { startActivity(Intent(this@MainChatActivity, TaskCenterActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(View(this).apply { setBackground(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x002C504E, 0x884B8279.toInt(), 0x002C504E))) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(7) })
        root.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(1) })
        animateIn(dock, 120)
        setContentView(root)
        addMessage("ai", "Hi. I’m Orion. We can talk naturally here; phone-control commands are handled with a separate permission-aware workflow.")
    }

    override fun onResume() {
        super.onResume()
        if (livePulse?.isStarted != true) livePulse?.start()
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

    override fun onPause() {
        livePulse?.cancel()
        super.onPause()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 44 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    val granted = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (granted != 0) contentResolver.takePersistableUriPermission(uri, granted)
                } catch (_: Exception) {
                    // Some photo providers grant only temporary access; the current selection can still be used.
                }
                selectedImageUri = uri
                refreshImagePreview(uri)
            }
        }
    }

    private fun decodeThumbnail(uri: Uri, maxPixels: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxPixels || bounds.outHeight / sample > maxPixels) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshImagePreview(uri: Uri?) {
        val panel = imagePreviewPanel ?: return
        panel.removeAllViews()
        if (uri == null) {
            panel.visibility = View.GONE
            return
        }
        val thumbnail = ImageView(this).apply {
            setImageBitmap(decodeThumbnail(uri, dp(72)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF151E21.toInt(), 10, 0xFF2B3A3D.toInt())
            clipToOutline = true
            contentDescription = "Selected image preview"
        }
        panel.addView(thumbnail, LinearLayout.LayoutParams(dp(54), dp(54)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            addView(label("IMAGE ATTACHED", 10f, Theme.CYAN, true))
            addView(label("Orion will inspect this image", 12f, Theme.MUTED))
        }
        panel.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        val remove = label("×", 23f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Remove attached image"
            setPadding(dp(10), dp(2), dp(5), dp(2))
            setOnClickListener {
                selectedImageUri = null
                refreshImagePreview(null)
            }
        }
        panel.addView(remove, LinearLayout.LayoutParams(dp(38), dp(48)))
        panel.visibility = View.VISIBLE
    }

    private fun addImageBubble(uri: Uri) {
        val image = ImageView(this).apply {
            setImageBitmap(decodeThumbnail(uri, dp(280)))
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            maxHeight = dp(230)
            background = rounded(Theme.PANEL, 16, Theme.LINE)
            clipToOutline = true
            contentDescription = "Image sent to Orion"
        }
        val params = LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            gravity = Gravity.END
            setMargins(dp(38), dp(0), 0, dp(7))
        }
        messages.addView(image, params)
        image.alpha = 0f
        image.animate().alpha(1f).setDuration(180).start()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        val value = input.text.toString().trim()
        val imageUri = selectedImageUri
        if (value.isEmpty() && imageUri == null) return
        val service = PhoneAiService.instance
        if (service == null) {
            Toast.makeText(this, "Enable Orion in Accessibility settings first.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        input.setText("")
        if (imageUri != null) {
            val question = value.ifBlank { "Describe this image and explain anything important I should notice." }
            addMessage("me", question)
            addImageBubble(imageUri)
            selectedImageUri = null
            refreshImagePreview(null)
            service.analyzeImageFromMainChat(imageUri, question)
        } else {
            service.sendFromMainChat(value, conversationOnly = !commandMode)
        }
    }

    private fun restoreWelcome() {
        listOf(welcomePanel, promptRail).forEach { view ->
            view?.animate()?.cancel()
            view?.visibility = View.VISIBLE
            view?.alpha = 1f
            view?.translationY = 0f
        }
    }

    private fun addMessage(kind: String, text: String) {
        if (kind == "me") {
            listOf(welcomePanel, promptRail).forEach { view ->
                view?.let { panel ->
                    if (panel.visibility == View.VISIBLE) {
                        panel.animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(160)
                            .withEndAction { panel.visibility = View.GONE }.start()
                    }
                }
            }
        }
        val isNote = kind == "note"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isNote) 12 else 15), dp(if (isNote) 9 else 12), dp(if (isNote) 12 else 15), dp(if (isNote) 9 else 12))
            background = when {
                isNote -> rounded(Theme.PANEL, 13, Theme.LINE)
                kind == "me" -> rounded(0xFF20272A.toInt(), 19, 0xFF303A3E.toInt())
                kind == "ai" -> null
                else -> rounded(Theme.PANEL, 16, Theme.LINE)
            }
            elevation = 0f
        }
        if (isNote) {
            row.addView(label("LIVE ACTIVITY", 9f, Theme.CYAN, true).apply {
                letterSpacing = .09f
                setPadding(0, 0, 0, dp(3))
            })
        } else if (kind == "ai") {
            val identity = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(4))
            }
            identity.addView(label("Orion", 11f, Theme.MUTED, true))
            row.addView(identity)
        }
        val body = label(text, if (isNote) 12f else 15f, if (isNote) Theme.MUTED else Theme.TEXT).apply {
            setLineSpacing(dp(3).toFloat(), 1.035f)
            setTextIsSelectable(true)
            if (isNote) maxLines = 4
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        row.addView(body)
        // Keep both sides visually distinct and leave a little breathing room at the
        // screen edge. Assistant replies no longer become full-width slabs on phones.
        val screenWidth = resources.displayMetrics.widthPixels
        // Widths include safe horizontal gutters. The previous 96%-screen assistant
        // bubble plus its right margin could exceed the parent on narrow phones.
        val usableWidth = (screenWidth - dp(28)).coerceAtLeast(dp(220))
        val bubbleWidth = when {
            isNote -> -1
            kind == "me" -> (usableWidth * .78f).toInt()
            kind == "ai" -> (usableWidth * .94f).toInt()
            else -> (usableWidth * .90f).toInt()
        }
        val params = LinearLayout.LayoutParams(bubbleWidth, -2)
        params.width = if (isNote) ViewGroup.LayoutParams.MATCH_PARENT else bubbleWidth.coerceAtMost(usableWidth)
        params.gravity = if (kind == "me") Gravity.END else Gravity.START
        params.setMargins(
            if (kind == "me") dp(30) else 0,
            dp(if (isNote) 2 else 5),
            if (kind == "me") 0 else dp(if (isNote) 0 else 14),
            dp(if (isNote) 3 else 7)
        )
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
                OrbView.Mode.IDLE -> Triple("READY", Theme.MUTED, Pair(Theme.BG, Theme.LINE))
                OrbView.Mode.LISTENING -> Triple("LISTENING", Theme.CYAN, Pair(Theme.BG, Theme.LINE))
                OrbView.Mode.THINKING -> Triple("THINKING", Theme.MUTED, Pair(Theme.BG, Theme.LINE))
                OrbView.Mode.SPEAKING -> Triple("SPEAKING", Theme.CYAN, Pair(Theme.BG, Theme.LINE))
                OrbView.Mode.ACTING -> Triple("WORKING", Theme.WARN, Pair(Theme.BG, Theme.LINE))
                OrbView.Mode.WAITING_APPROVAL -> Triple("NEEDS APPROVAL", Theme.WARN, Pair(Theme.BG, Theme.LINE))
            }
            val short = state.first
            val accent = state.second
            val fill = state.third.first
            val border = state.third.second
            status.text = short.lowercase().replaceFirstChar { it.uppercase() }
            status.setTextColor(if (mode == OrbView.Mode.IDLE) Theme.MUTED else accent)
            status.background = null
            status.setPadding(0, dp(3), 0, dp(1))
            status.contentDescription = "Orion state: " + short
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
