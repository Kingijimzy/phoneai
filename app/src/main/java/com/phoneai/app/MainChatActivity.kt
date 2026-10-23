package com.phoneai.app

import androidx.activity.ComponentActivity

import android.Manifest
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

/** Full-screen conversation. The floating orb keeps its separate, compact command panel. */
class MainChatActivity : ComponentActivity(), PhoneAiService.MainChatListener {
    companion object {
        const val EXTRA_PICK_IMAGE = "com.phoneai.app.PICK_IMAGE_ON_OPEN"
    }
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
    private fun glass(radius: Int, stroke: Int = 0x555F5F5F) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xF0232323.toInt(), 0xEA171717.toInt(), 0xF00C0C0C.toInt())
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
        talkModeChip.background = rounded(if (talkSelected) 0xFF2B2B2B.toInt() else 0xFF161616.toInt(),
            16, if (talkSelected) 0xFF787878.toInt() else 0xFF333333.toInt())
        commandModeChip.background = rounded(if (commandMode) 0xFF2B2B2B.toInt() else 0xFF161616.toInt(),
            16, if (commandMode) 0xFF787878.toInt() else 0xFF333333.toInt())
        talkModeIcon.tint = if (talkSelected) Theme.CYAN else Theme.MUTED
        commandModeIcon.tint = if (commandMode) Theme.CYAN else Theme.MUTED
        talkModeText.setTextColor(if (talkSelected) Theme.CYAN else Theme.MUTED)
        commandModeText.setTextColor(if (commandMode) Theme.CYAN else Theme.MUTED)
        if (::input.isInitialized) input.hint = if (commandMode) "Tell Orion what to do…" else "Talk to Orion…"
        if (::modeHint.isInitialized) {
            modeHint.text = if (commandMode) {
                "Phone actions and deliberate web research · sensitive actions ask first"
            } else {
                "Conversation by default · Orion automatically routes clear tool requests"
            }
            modeHint.setTextColor(if (commandMode) Theme.CYAN else Theme.MUTED)
        }
    }

    private fun refreshPersonalityLabel(view: TextView) {
        val profile = CharacterProfile.get(Prefs.characterProfile(this))
        view.text = profile.label.substringBefore(" — ").uppercase()
        view.background = glass(12, 0x445F5F5F)
        view.setPadding(dp(8), dp(5), dp(8), dp(5))
        view.setTextColor(Theme.MUTED)
    }

    private fun historyActionButton(text: String, action: () -> Unit): TextView = label(text, 13f, 0xFFE2E2E2.toInt(), true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(0xFF202020.toInt(), 12, 0xFF454545.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun showChatHistory() {
        val chats = ChatHistoryStore.archived(this)
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(0xFF090909.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        heading.addView(label("CHAT ARCHIVE", 11f, 0xFF999999.toInt(), true))
        heading.addView(label("Your conversations", 25f, 0xFFF4F4F4.toInt(), true).apply { setPadding(0, dp(4), 0, dp(3)) })
        heading.addView(label("${chats.size} saved chats · stored on this device", 12f, 0xFF8B8B8B.toInt()))
        header.addView(heading)
        val close = historyActionButton("Close") { dialog.dismiss() }
        header.addView(close)
        root.addView(header)
        val divider = View(this).apply { setBackgroundColor(0xFF292929.toInt()) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(16); bottomMargin = dp(12) })
        val list = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (chats.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(36), dp(22), dp(36))
                background = rounded(0xFF151515.toInt(), 20, 0xFF303030.toInt())
            }
            empty.addView(label("◷", 34f, 0xFFBDBDBD.toInt(), true).apply { gravity = Gravity.CENTER })
            empty.addView(label("No saved conversations yet", 18f, 0xFFF1F1F1.toInt(), true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(7)) })
            empty.addView(label("When you start a new chat, Orion keeps the previous conversation here.", 13f, 0xFF9B9B9B.toInt()).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
            rows.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        } else {
            val dates = java.text.SimpleDateFormat("d MMM yyyy · h:mm a", java.util.Locale.getDefault())
            chats.forEachIndexed { index, chat ->
                val preview = chat.messages.lastOrNull()?.second.orEmpty().replace("\\n", " ").trim()
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = rounded(0xFF171717.toInt(), 18, 0xFF303030.toInt())
                    isClickable = true
                    isFocusable = true
                }
                val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(label(chat.title.ifBlank { "Untitled conversation" }, 16f, 0xFFF0F0F0.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(label("›", 25f, 0xFFBDBDBD.toInt(), true))
                card.addView(top)
                card.addView(label(dates.format(java.util.Date(chat.timestamp)) + "  ·  " + chat.messages.size + " messages", 11f, 0xFF8E8E8E.toInt()).apply { setPadding(0, dp(7), 0, dp(8)) })
                card.addView(label(preview.ifBlank { "Open conversation to view messages" }, 13f, 0xFFB6B6B6.toInt()).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                card.setOnClickListener { showArchivedConversation(chat.title, chat.messages, dates.format(java.util.Date(chat.timestamp))) }
                rows.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
                animateIn(card, (index.coerceAtMost(6) * 35L))
            }
        }
        list.addView(rows)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, -1)
        dialog.show()
        dialog.window?.setBackgroundDrawable(rounded(0xFF090909.toInt(), 0))
        dialog.window?.setLayout(-1, -1)
        animateIn(root)
    }

    private fun showArchivedConversation(title: String, messages: List<Pair<String, String>>, date: String) {
        val dialog = android.app.Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            setBackgroundColor(0xFF090909.toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = historyActionButton("‹ History") { dialog.dismiss(); showChatHistory() }
        header.addView(back)
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
        titleBox.addView(label(title.ifBlank { "Conversation" }, 16f, 0xFFF4F4F4.toInt(), true).apply { maxLines = 2 })
        titleBox.addView(label(date, 11f, 0xFF929292.toInt()))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(0xFF292929.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(10) })
        val scrollView = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), dp(14)) }
        messages.forEachIndexed { index, item ->
            val mine = item.first == "me"
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(10), dp(13), dp(10))
                background = rounded(if (mine) 0xFF292929.toInt() else 0xFF151515.toInt(), 16, if (mine) 0xFF454545.toInt() else 0xFF2B2B2B.toInt())
            }
            bubble.addView(label(if (mine) "YOU" else "ORION", 10f, if (mine) 0xFFBDBDBD.toInt() else 0xFFE4E4E4.toInt(), true))
            bubble.addView(label(item.second, 14f, 0xFFF0F0F0.toInt()).apply { setPadding(0, dp(5), 0, 0) })
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (mine) Gravity.END else Gravity.START }
            line.addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels - dp(68)).coerceAtLeast(dp(180)), -2))
            transcript.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            animateIn(line, (index.coerceAtMost(5) * 25L))
        }
        scrollView.addView(transcript)
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(historyActionButton("Copy transcript") {
            val body = messages.joinToString("\\n\\n") { (kind, text) -> (if (kind == "me") "YOU" else "ORION") + "\\n" + text }
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Orion chat history", body))
            Toast.makeText(this@MainChatActivity, "Transcript copied", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        actions.addView(historyActionButton("Continue chat") {
            PhoneAiService.instance?.restoreArchivedChat(messages)
                ?: Toast.makeText(this@MainChatActivity, "Open Orion assistant service first, then try again.", Toast.LENGTH_LONG).show()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(-1, -1)
        dialog.window?.setBackgroundDrawable(rounded(0xFF090909.toInt(), 0))
        dialog.window?.setLayout(-1, -1)
        animateIn(root)
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
                intArrayOf(0xF2212121.toInt(), 0xF00F0F0F.toInt())).apply { setStroke(dp(1), 0x555F5F5F); cornerRadius = dp(22).toFloat() }
        }
        val eyebrow = label("ORION PERSONALITY SYSTEM", 10f, 0xFFBBBBBB.toInt(), true).apply {
            letterSpacing = .13f
            setPadding(0, dp(4), 0, dp(7))
        }
        dialogRoot.addView(eyebrow)
        dialogRoot.addView(label("Choose your mode", 23f, 0xFFF0FFFA.toInt(), true).apply {
            setPadding(0, 0, 0, dp(4))
        })
        dialogRoot.addView(label("Same Orion. Different way of thinking and communicating.", 12f, 0xFFB1B1B1.toInt()).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })
        val currentLabel = label("", 10f, 0xFFD3D3D3.toInt(), true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = glass(10, 0x445F5F5F)
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
                    background = rounded(if (selected) 0xFF272727.toInt() else 0xFF161616.toInt(),
                        16, if (selected) 0xFF6A6A6A.toInt() else 0xFF333333.toInt())
                    alpha = if (selected) 1f else .94f
                }
                iconViews[profile.id]?.apply {
                    background = rounded(if (selected) 0xFF323232.toInt() else 0xFF1D1D1D.toInt(), 12,
                        if (selected) 0xFF6A6A6A.toInt() else 0xFF333333.toInt())
                    tint = if (selected) Theme.CYAN else Theme.MUTED
                }
                checkViews[profile.id]?.apply {
                    icon = if (selected) "check" else "spark"
                    alpha = if (selected) 1f else 0f
                    tint = 0xFFD7D7D7.toInt()
                    background = if (selected) rounded(0xFF2D2D2D.toInt(), 20, 0xFF595959.toInt()) else null
                }
                nameViews[profile.id]?.setTextColor(if (selected) 0xFFFAFAFA.toInt() else 0xFFDDDDDD.toInt())
                descViews[profile.id]?.setTextColor(if (selected) 0xFFDCDCDC.toInt() else 0xFF9E9E9E.toInt())
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
                tint = 0xFFB1B1B1.toInt()
                contentDescription = null
            }
            row.addView(iconView, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(11) })
            val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val name = label(profile.label.substringBefore(" — "), 14f, 0xFFDDDDDD.toInt(), true)
            val desc = label(descriptions[profile.id].orEmpty(), 11f, 0xFF9E9E9E.toInt()).apply {
                setLineSpacing(dp(1).toFloat(), 1f)
                setPadding(0, dp(3), dp(2), 0)
            }
            textCol.addView(name)
            textCol.addView(desc)
            row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
            val check = OrionIconView(this).apply {
                icon = "check"
                tint = 0xFFD7D7D7.toInt()
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
        dialogRoot.addView(label("PERSONALITY IS SAVED ON THIS PHONE", 9f, 0xFF7F7F7F.toInt(), true).apply {
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
            setPadding(dp(16), dp(7), dp(16), dp(6))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(1), dp(2), dp(1), dp(3))
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
        titleCol.addView(label("PERSONAL INTELLIGENCE", 8f, Theme.MUTED, true).apply {
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
            background = rounded(0xFF1D1D1D.toInt(), 14, 0xFF7B7B7B.toInt())
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
        val history = label("History", 11f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            contentDescription = "View previous chats"
            isClickable = true
            isFocusable = true
            setOnClickListener { showChatHistory() }
        }
        header.addView(history, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(3) })
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
            setPadding(dp(2), dp(1), dp(2), dp(4))
            background = null
        }
        // The reference uses a single expressive animated centerpiece; keep it compact
        // so the conversation and composer remain the focus on a 720px-wide phone.
        intro.addView(OrbView(this).apply {
            contentDescription = "Animated Orion energy core"
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(88), dp(70)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(1)
        })
        intro.addView(label("ORION", 10f, Theme.MUTED, true).apply {
            letterSpacing = .18f
            gravity = Gravity.CENTER
        })
        intro.addView(label("What’s on your mind?", 22f, Theme.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, dp(2))
        })
        intro.addView(label("Ask naturally. I’ll keep conversation separate from actions and web research.", 12f, Theme.MUTED).apply {
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
            Triple("Memory", "memory", "Recall my saved memories and summarize the relevant entries. Use memory IDs if I ask to update or forget one; confirm before deleting anything."),
            Triple("Skills", "skills", "List my registered Orion skills, show which are enabled, their purpose and allowed tools. Do not run a skill."),
            Triple("Tasks", "tasks", "List my saved tasks with their status and priority, then help me create, update, or complete a task if I ask."),
            Triple("Browser", "browser", ""),
            Triple("Teach mode", "record", "")
        ).forEach { (name, iconName, prompt) ->
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(7), dp(13), dp(7))
                background = glass(16, 0x44484848)
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
                    else -> {
                        commandMode = name == "Research" || name == "Phone command" || name == "Memory" || name == "Skills" || name == "Tasks"
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
            background = glass(16, 0x555F5F5F)
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
            background = glass(24, 0x66686868)
            elevation = dp(3).toFloat()
            setPadding(dp(7), dp(4), dp(6), dp(4))
        }
        input = EditText(this).apply {
            hint = "Message Orion…"
            setHintTextColor(Theme.MUTED)
            setTextColor(Theme.TEXT)
            textSize = 16f
            background = null
            minHeight = dp(42)
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
            background = glass(20, 0x445F5F5F)
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
        send = label("↑", 23f, 0xFF0F0F0F.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFE9E9E9.toInt(), 0xFFA0A0A0.toInt())).apply { cornerRadius = dp(23).toFloat(); setStroke(dp(1), 0xFFEFEFEF.toInt()) }
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
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = rounded(0xFF111111.toInt(), 22, 0xFF333333.toInt())
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
                background = if (selected) rounded(0xFF1F1F1F.toInt(), 14, Theme.LINE) else null
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
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFE6E6E6.toInt(), 0xFFC1C1C1.toInt())).apply { shape = GradientDrawable.OVAL; setStroke(dp(1), 0xFFF8F8F8.toInt()) }
            elevation = dp(1).toFloat()
            addView(OrionIconView(this@MainChatActivity).apply { icon = "call"; tint = 0xFF0E0E0E.toInt(); contentDescription = null }, LinearLayout.LayoutParams(dp(24), dp(24)))
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
        root.addView(View(this).apply { setBackground(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00484848, 0x88767676.toInt(), 0x00484848))) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(7) })
        root.addView(dock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(1) })
        animateIn(dock, 120)
        composeChatActive = true
        val composeRoot = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OrionComposeChatScreen(
                    lines = composeLines,
                    status = composeStatus.value,
                    commandMode = composeCommandMode.value,
                    selectedImage = composeSelectedImage.value,
                    onBack = { finish() },
                    onHistory = { showChatHistory() },
                    onNewChat = {
                        val service = PhoneAiService.instance
                        if (service != null) service.startNewChat()
                        else {
                            ChatHistoryStore.archiveAndClear(this@MainChatActivity, composeLines.map { it.kind to it.text })
                            composeLines.clear()
                            Toast.makeText(this@MainChatActivity, "New chat ready", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onModeChange = { enabled -> commandMode = enabled; composeCommandMode.value = enabled },
                    onSend = { message -> input.setText(message); sendMessage() },
                    onPickImage = { val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }; startActivityForResult(Intent.createChooser(picker, "Choose an image"), 44) }, onRemoveImage = { selectedImageUri = null; composeSelectedImage.value = null }, onVoice = { startActivity(Intent(this@MainChatActivity, LiveConversationActivity::class.java)) }, onBrowser = { startActivity(Intent(this@MainChatActivity, BrowserActivity::class.java)) }, onTasks = { startActivity(Intent(this@MainChatActivity, TaskCenterActivity::class.java)) }, onStop = { if (::stop.isInitialized) stop.performClick() }
                )
            }
        }
        setContentView(composeRoot)
        addMessage("ai", "Hi. I’m Orion. We can talk naturally here; phone-control commands are handled with a separate permission-aware workflow.")
        if (intent.getBooleanExtra(EXTRA_PICK_IMAGE, false)) {
            window.decorView.postDelayed({
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(picker, "Choose an image for Orion"), 44)
            }, 260L)
        }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
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
                composeSelectedImage.value = uri
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
            background = rounded(0xFF1C1C1C.toInt(), 10, 0xFF373737.toInt())
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
        if (composeChatActive) { composeLines.add(OrionChatLine(System.nanoTime(), "me", "", uri)); return }
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
            service.sendFromMainChat(
                value,
                conversationOnly = !commandMode,
                autoRoute = !commandMode
            )
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
        if (composeChatActive) { composeLines.add(OrionChatLine(System.nanoTime(), kind, text)); return }
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
                kind == "me" -> rounded(0xFF262626.toInt(), 19, 0xFF383838.toInt())
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

    override fun onTranscriptRestored(messages: List<Pair<String, String>>) {
        runOnUiThread {
            composeLines.clear()
            messages.forEachIndexed { index, pair ->
                val (kind, text) = pair
                composeLines.add(OrionChatLine(System.nanoTime() + index, kind, text))
            }
        }
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
            composeStatus.value = short
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
