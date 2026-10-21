package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Orion's control room: turn things on, save keys, tune the voice, manage memory, test recording. */
class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var chatBtn: Button
    private lateinit var visionBtn: Button
    private lateinit var nameBtn: Button
    private lateinit var memBtn: Button
    private lateinit var characterBtn: Button
    private lateinit var routBtn: Button
    private lateinit var skillBtn: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    /** Orion-styled choice sheet replacing stock Android list dialogs. */
    private fun showChoiceDialog(title: String, subtitle: String, items: List<String>, onPick: (Int) -> Unit, onClose: () -> Unit = {}) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF07110F.toInt(), 0xFF0A1D19.toInt(), 0xFF06100F.toInt()))
        }
        outer.addView(text("ORION  /  SELECT", 10f, Theme.CYAN, true).apply { letterSpacing = .12f; setPadding(0, 0, 0, dp(6)) })
        outer.addView(text(title, 21f, Theme.TEXT, true))
        if (subtitle.isNotBlank()) outer.addView(text(subtitle, 12f, Theme.MUTED).apply { setPadding(0, dp(4), 0, dp(12)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var sheet: AlertDialog? = null
        items.forEachIndexed { index, label ->
            val pieces = label.split(" · ", limit = 2)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12))
                background = GradientDrawable().apply { setColor(0xFF0A1715.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF203D37.toInt()) }
                isClickable = true; isFocusable = true; contentDescription = label
            }
            row.addView(text(pieces[0], 14f, Theme.TEXT, true))
            if (pieces.size > 1) row.addView(text(pieces[1], 11f, Theme.MUTED).apply { setPadding(0, dp(4), 0, 0) })
            row.setOnClickListener { sheet?.dismiss(); onPick(index) }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS; addView(list, -1, -2) }
        val height = (resources.displayMetrics.heightPixels - dp(270)).coerceAtLeast(dp(180)).coerceAtMost(dp(470))
        outer.addView(scroll, LinearLayout.LayoutParams(-1, height))
        val dialog = AlertDialog.Builder(this).setView(outer).setNegativeButton("CLOSE") { _, _ -> onClose() }.create()
        sheet = dialog
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2) }
        dialog.show(); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
    }

    private fun showInfoDialog(title: String, message: String, positive: String = "GOT IT", onPositive: () -> Unit = {}) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(8)); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF07110F.toInt(), 0xFF0A1D19.toInt())) }
        body.addView(text("ORION  /  INFORMATION", 10f, Theme.CYAN, true).apply { letterSpacing = .1f; setPadding(0, 0, 0, dp(7)) })
        body.addView(text(title, 20f, Theme.TEXT, true))
        val detail = text(message, 13f, Theme.MUTED).apply { setPadding(0, dp(8), 0, dp(4)); setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(detail); isFillViewport = true }
        body.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels - dp(360)).coerceAtLeast(dp(100)).coerceAtMost(dp(360))))
        val dialog = AlertDialog.Builder(this).setView(body).setPositiveButton(positive) { _, _ -> onPositive() }.setNegativeButton("CLOSE", null).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawableResource(android.R.color.transparent); dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2) }
        dialog.show(); dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 14f
        b.setTextColor(if (primary) Theme.ON_ACCENT else Theme.TEXT)
        val g = GradientDrawable()
        g.setColor(if (primary) Theme.CYAN else Theme.PANEL2)
        g.cornerRadius = dp(12).toFloat()
        if (!primary) g.setStroke(dp(1), Theme.LINE)
        b.background = g
        b.setOnClickListener { onClick() }
        return b
    }

    private fun add(col: LinearLayout, v: View, top: Int = 10) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(top), 0, 0)
        col.addView(v, lp)
    }

    /** A rounded section with a title. Returns the inside column. */
    private fun card(parent: LinearLayout, title: String, note: String? = null): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable()
        g.setColor(Theme.PANEL)
        g.cornerRadius = dp(20).toFloat()
        g.setStroke(dp(1), 0xFF2C504E.toInt())
        c.background = g
        c.setPadding(dp(14), dp(12), dp(14), dp(13))
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(OrionIconView(this).apply {
            icon = settingsIcon(title)
            tint = Theme.CYAN
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(9) })
        heading.addView(text(title, 17f, Theme.CYAN, true))
        c.addView(heading)
        if (note != null) {
            val n = text(note, 13f, Theme.MUTED)
            n.setPadding(dp(31), dp(4), 0, 0)
            c.addView(n)
        }
        val delay = (parent.childCount.coerceAtMost(8) * 35L)
        c.alpha = 0f
        c.translationY = dp(7).toFloat()
        add(parent, c, 10)
        c.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(230)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        return c
    }

    private fun settingsIcon(title: String): String {
        val t = title.lowercase()
        return when {
            "access" in t || "permission" in t || "privacy" in t -> "shield"
            "provider" in t || "model" in t || "ai health" in t -> "spark"
            "voice" in t || "character" in t -> "voice"
            "memory" in t || "routine" in t -> "memory"
            "skill" in t -> "skills"
            "integrat" in t || "telegram" in t || "mcp" in t -> "network"
            "record" in t || "screen" in t -> "browser"
            "undo" in t || "history" in t -> "clock"
            else -> "settings"
        }
    }

    private fun switchRow(col: LinearLayout, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
        val sw = Switch(this)
        sw.text = label
        sw.setTextColor(Theme.TEXT)
        sw.isChecked = on
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        add(col, sw, 10)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF02080A.toInt(), 0xFF071311.toInt(), 0xFF0A1715.toInt()))
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(24))
        sv.addView(col)
        setContentView(sv)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        header.addView(text("SETTINGS  /  ORION", 11f, Theme.CYAN, true))
        header.addView(text("Your workspace, your rules.", 25f, Theme.TEXT, true).apply {
            setPadding(0, dp(5), 0, dp(3))
        })
        header.addView(text("Manage AI providers, voice, memory, skills, device access, and integrations.", 13f, Theme.MUTED))
        add(col, header, 0)
        add(col, button("‹  Back to Orion", false) { finish() }, 4)
        
        // ---- 1. setup
        val s1 = card(col, "Access & permissions")
        status = text("", 13f, Theme.OK)
        status.typeface = Typeface.MONOSPACE
        add(s1, status, 8)
        add(
            s1,
            text(
                "Turn on Orion AI in Accessibility. If it says Restricted setting: App info of this app, " +
                    "three dots, Allow restricted settings, then try again.",
                12f, Theme.MUTED
            ),
            8
        )
        add(s1, button("Open Accessibility settings", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, 8)
        add(s1, button("Allow microphone and notifications", false) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        }, 8)
        add(s1, button("Open Orion", true) {
            val s = PhoneAiService.instance
            if (s == null) {
                Toast.makeText(this, "Turn on Accessibility first.", Toast.LENGTH_LONG).show()
            } else {
                s.showPanel()
                moveTaskToBack(true)
            }
        }, 8)

        // ---- 2. AI services
        val s2 = card(
            col, "AI providers & models",
            "13 online services plus a free local AI. Add 3 or more free keys (Groq, Cerebras, Mistral, Gemini, OpenRouter): Orion spreads requests across all of them so none runs out, and switches instantly when one is busy. Simple commands (open apps, flashlight, volume, alarms...) need no AI at all."
        )
        add(s2, button("Get a free Gemini key (opens website)", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }, 10)
        add(s2, button("Add or change an API key", true) { keyDialog() }, 8)
        chatBtn = button("", false) { providerDialog(false) }
        add(s2, chatBtn, 8)
        visionBtn = button("", false) { providerDialog(true) }
        add(s2, visionBtn, 8)
        switchRow(s2, "Auto-switch to another service if one fails", Prefs.autoSwitch(this)) { Prefs.setAutoSwitch(this, it) }
        switchRow(s2, "Conserve API quota (fewer retries; recommended)", Prefs.quotaSaver(this)) {
            Prefs.setQuotaSaver(this, it)
        }
        add(s2, button("AI health, provider status & quota", false) { showAiHealth() }, 10)
        add(s2, button("Test the AI", false) { testAi() }, 10)
        add(s2, button("Choose chat model", false) { pickModel(false) }, 8)
        add(s2, button("Choose vision model", false) { pickModel(true) }, 8)

        // ---- 3. voice
        val s3 = card(col, "Voice & response style")
        switchRow(s3, "Speak the answers", Prefs.voice(this)) { Prefs.setVoice(this, it) }
        switchRow(s3, "Deeper voice", Prefs.deepVoice(this)) { Prefs.setDeepVoice(this, it) }
        switchRow(s3, "Hands-free: keep listening after each answer", Prefs.hands(this)) { Prefs.setHands(this, it) }
        switchRow(s3, "Wake word: say \"Orion\" (experimental, uses battery)", Prefs.wake(this)) {
            Prefs.setWake(this, it)
            if (it) {
                Toast.makeText(
                    this, "Turn Accessibility off and on once so Orion starts listening.", Toast.LENGTH_LONG
                ).show()
            }
        }
        add(s3, text("Speaking speed", 13f, Theme.MUTED), 12)
        val bar = SeekBar(this)
        bar.max = 100
        bar.progress = ((Prefs.rate(this) - 0.5f) * 100f).toInt().coerceIn(0, 100)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) Prefs.setRate(this@SettingsActivity, 0.5f + p / 100f)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        add(s3, bar, 2)
        characterBtn = button("Response personality profile", false) { characterDialog() }
        add(s3, characterBtn, 8)
        add(s3, button("Choose a voice", false) { voiceDialog() }, 8)
        add(s3, button("Hear Orion's voice", false) { testVoice() }, 8)

        // ---- 4. memory
        val s4 = card(
            col, "Memory, routines & skills",
            "Orion remembers your preferences, projects, decisions and unfinished work, even after you close the app. " +
                "You can view, edit or delete everything."
        )
        nameBtn = button("", false) { nameDialog() }
        add(s4, nameBtn, 10)
        memBtn = button("", true) { memoryDialog() }
        add(s4, memBtn, 8)
        add(
            s4,
            text(
                "Routines: after Orion finishes a task, say \"save this as a routine called morning check\". " +
                    "Later say \"run morning check\" and it repeats the steps with NO AI, so no limits and no waiting.",
                12f, Theme.MUTED
            ),
            12
        )
        routBtn = button("", false) { routinesDialog() }
        add(s4, routBtn, 8)
        skillBtn = button("", false) { skillsDialog() }
        add(s4, skillBtn, 8)

        // ---- 5. recording
        val s5 = card(
            col, "Screen recording",
            "Say \"record my screen\" to Orion, or test here. Android asks permission each time. Videos are saved in Movies/Orion."
        )
        add(s5, button("Start a test recording", false) {
            if (RecordService.state == "recording") {
                Toast.makeText(this, "Already recording.", Toast.LENGTH_SHORT).show()
            } else {
                RecordService.state = "waiting"
                startActivity(Intent(this, RecordRequestActivity::class.java))
            }
        }, 10)
        add(s5, button("Stop recording", false) {
            val svc = RecordService.instance
            if (svc == null || RecordService.state != "recording") {
                Toast.makeText(this, "Not recording.", Toast.LENGTH_SHORT).show()
            } else {
                svc.stopRecording()
                Toast.makeText(this, "Saving to Movies/Orion...", Toast.LENGTH_LONG).show()
            }
        }, 8)

        // ---- 6. browser and automation
        val browser = card(
            col, "Browser & automation",
            "Open a website, then ask Orion to research it or navigate visible page elements. Automation uses the Android accessibility service, checks the screen between steps, and stops for sensitive or uncertain actions."
        )
        add(browser, button("Open browser", true) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
            } catch (_: Exception) {
                Toast.makeText(this, "No browser app is available.", Toast.LENGTH_LONG).show()
            }
        }, 8)
        add(browser, text(
            "Try: “Research this page”, “Find the settings button”, “Scroll down and summarize”, or “Wait until the results load”. For sign-in, payment, posting, sending, purchases, or account changes, Orion must stop and ask you.",
            12f, Theme.MUTED
        ), 8)

        // ---- 7. local device utilities
        val s6 = card(
            col, "Phone diagnostics & files",
            "Network Radar, Game Sentinel, and user-selected file handling. These tools run locally on your phone."
        )
        add(s6, button("Open Network Radar, Game Sentinel & Files", true) {
            startActivity(Intent(this, DeviceUtilitiesActivity::class.java))
        }, 10)

        // ---- 7. external integrations
        val s7 = card(
            col, "Integrations & workflows",
            "Connect a Telegram bot safely, inspect connector status, and see which services are genuinely available. Bot tokens are encrypted with Android Keystore and never sent to the AI model."
        )
        add(s7, button("Configure Telegram bot", true) { telegramDialog() }, 8)
        add(s7, button("Configure remote MCP server", true) { mcpDialog() }, 8)
        add(s7, button("Discover MCP tools", false) {
            Toast.makeText(this, "Contacting MCP server...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = McpConnector.listTools(this)
                runOnUiThread {
                    showInfoDialog("MCP tools", result)
                }
            }.start()
        }, 8)
        add(s7, button("Check Telegram inbox (read-only)", false) {
            Toast.makeText(this, "Checking saved Telegram chat...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = TelegramBridge.checkUpdates(this)
                runOnUiThread {
                    showInfoDialog("Telegram inbox", result)
                }
            }.start()
        }, 8)
        add(s7, button("Check Telegram bot connection", false) {
            Toast.makeText(this, "Checking Telegram...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = TelegramBridge.status(this)
                runOnUiThread {
                    showInfoDialog("Telegram connection", result)
                }
            }.start()
        }, 8)
        add(s7, button("Show integrations and MCP status", false) {
            showInfoDialog("Connector status", IntegrationStatus.summary(this))
        }, 8)

        add(
            col,
            text(
                "Safety: Orion asks before sending, paying, buying or deleting, and never types passwords or codes. " +
                    "Tap Stop any time. Free AI services may read what you send them, so avoid private screens. " +
                    "Games can ban accounts that use automation.",
                12f, Theme.MUTED
            ),
            20
        )
        when (intent.getStringExtra("open_section")) {
            "memory" -> memoryDialog()
            "skills" -> skillsDialog()
            "routines" -> routinesDialog()
            "character" -> characterDialog()
            "voice" -> voiceDialog()
            "providers" -> providerDialog(false)
            "integrations" -> telegramDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val acc = if (PhoneAiService.instance != null) "ON" else "OFF"
        val mic = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "allowed" else "not allowed"
        val sb = StringBuilder()
        sb.append("Accessibility: $acc\n")
        sb.append("Microphone:    $mic\n")
        sb.append("Keys saved:    ")
        val saved = Providers.all.filter { Prefs.hasKey(this, it.id) }.map { it.label }
        sb.append(if (saved.isEmpty()) "none yet" else saved.joinToString(", "))
        status.text = sb.toString()
        chatBtn.text = "Chat AI: " + Prefs.provider(this, false).label + "  (tap to change)"
        visionBtn.text = "Screen-vision AI: " + Prefs.provider(this, true).label + "  (tap to change)"
        val n = Prefs.userName(this)
        nameBtn.text = if (n.isEmpty()) "Tell Orion your name" else "Your name: $n  (tap to change)"
        memBtn.text = "View and edit memory (" + MemoryStore.all(this).size + ")"
        routBtn.text = "My routines (" + MacroStore.names(this).size + ")"
        skillBtn.text = "Skill Studio · " + SkillRegistry.list(this).count { it.enabled } + " enabled / " + SkillRegistry.list(this).size + " total"
    }

    // ------------------------------------------------------------ keys and providers

    /** Pick a service using the same dark, card-based selector language as Orion's main UI. */
    private fun keyDialog() {
        providerPickerDialog(
            title = "Add an AI provider",
            subtitle = "Choose a service. Saved keys stay on this phone.",
            providers = Providers.all,
            showKeyState = true
        ) { pasteKey(it) }
    }

    private fun providerPickerDialog(
        title: String,
        subtitle: String,
        providers: List<Provider>,
        showKeyState: Boolean,
        onPick: (Provider) -> Unit
    ) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF07110F.toInt(), 0xFF0A1D19.toInt(), 0xFF06100F.toInt()))
        }
        outer.addView(text("ORION  /  AI CONNECTIONS", 10f, Theme.CYAN, true).apply {
            letterSpacing = .12f
            setPadding(0, 0, 0, dp(7))
        })
        outer.addView(text(title, 22f, Theme.TEXT, true))
        outer.addView(text(subtitle, 12f, Theme.MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        providers.forEach { provider ->
            val saved = Prefs.hasKey(this, provider.id)
            val selected = Prefs.provider(this, false).id == provider.id ||
                Prefs.provider(this, true).id == provider.id
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(11), dp(11))
                background = GradientDrawable().apply {
                    setColor(if (saved) 0xFF10342B.toInt() else 0xFF0A1715.toInt())
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), if (saved) 0xFF167C66.toInt() else 0xFF203D37.toInt())
                }
                isClickable = true
                isFocusable = true
                contentDescription = provider.label
            }
            val icon = text(if (provider.noKey) "⌂" else "✦", 19f, Theme.CYAN, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0xFF0A4A3A.toInt()); cornerRadius = dp(11).toFloat()
                }
            }
            card.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(11) })
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(text(provider.label, 14f, Theme.TEXT, true))
            val state = when {
                provider.noKey && saved -> "LOCAL SERVICE · ENABLED"
                provider.noKey -> "LOCAL SERVICE · SET UP"
                saved -> "API KEY SAVED ON THIS PHONE"
                else -> "API KEY NOT ADDED"
            }
            details.addView(text(state, 10f, if (saved) Theme.CYAN else Theme.MUTED, true).apply {
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) card.addView(text("✓", 17f, Theme.CYAN, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(26), dp(30)))
            card.setOnClickListener { onPick(provider) }
            cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(cards, -1, -2)
        }
        val height = (resources.displayMetrics.heightPixels - dp(240)).coerceAtLeast(dp(260)).coerceAtMost(dp(500))
        outer.addView(scroll, LinearLayout.LayoutParams(-1, height))
        val dialog = AlertDialog.Builder(this).setView(outer).setNegativeButton("CLOSE", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
    }

    private fun pasteKey(p: Provider) {
        if (p.noKey) {
            localDialog(p)
            return
        }
        val box = EditText(this).apply {
            hint = p.keyHint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            background = rounded(Theme.PANEL2, 12, Theme.LINE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF07110F.toInt(), 0xFF0A1D19.toInt()))
            addView(text("ORION  /  PROVIDER SECURITY", 10f, Theme.CYAN, true).apply {
                letterSpacing = .1f; setPadding(0, 0, 0, dp(7))
            })
            addView(text(p.label + " API key", 20f, Theme.TEXT, true))
            addView(text(p.info + "\n\nPaste the key below. It is saved in app preferences and is not displayed back in plain text.", 12f, Theme.MUTED)
                .apply { setPadding(0, dp(5), 0, dp(10)) })
            addView(box)
        }
        val dialog = AlertDialog.Builder(this).setView(wrap)
            .setPositiveButton("SAVE KEY", null).setNegativeButton("CANCEL", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val k = box.text.toString().trim()
                if (k.isEmpty()) {
                    box.error = "Paste an API key first"
                    return@setOnClickListener
                }
                val chatNeeds = !Prefs.hasKey(this, Prefs.provider(this, false).id)
                val visionNeeds = !Prefs.hasKey(this, Prefs.provider(this, true).id)
                Prefs.setKey(this, p.id, k)
                if (chatNeeds) Prefs.setProvider(this, false, p.id)
                if (visionNeeds && p.hasVision) Prefs.setProvider(this, true, p.id)
                dialog.dismiss()
                Toast.makeText(this, p.label + " key saved.", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
    }

    private fun providerDialog(vision: Boolean) {
        val providers = Providers.all.filter { !vision || it.hasVision }
        providerPickerDialog(
            title = if (vision) "Screen-vision AI" else "Chat AI provider",
            subtitle = if (vision)
                "Choose a provider that supports image understanding."
            else "Choose which service answers Orion's messages.",
            providers = providers,
            showKeyState = true
        ) { provider ->
            Prefs.setProvider(this, vision, provider.id)
            if (!Prefs.hasKey(this, provider.id)) {
                Toast.makeText(this, "Add a " + provider.label + " key first.", Toast.LENGTH_LONG).show()
                if (!provider.noKey) pasteKey(provider)
                else localDialog(provider)
            }
            refresh()
        }
    }

    /** Shows every model the chosen service offers; tap one to use it. */
    private fun pickModel(vision: Boolean) {
        val p = Prefs.provider(this, vision)
        Toast.makeText(this, "Loading " + p.label + " models...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val nim = Nim(this)
                val all = nim.allModels(p)
                val list = if (vision) all.sortedBy { if (nim.isVision(it)) 0 else 1 } else all
                runOnUiThread {
                    if (list.isEmpty()) {
                        Toast.makeText(this, "No models found.", Toast.LENGTH_LONG).show()
                    } else {
                        showChoiceDialog(
                            p.label + (if (vision) " vision model" else " chat model"),
                            "Choose the model Orion should use for ${if (vision) "image understanding" else "conversation"}.",
                            list,
                            { which ->
                                Prefs.setModel(this, p, vision, list[which])
                                Toast.makeText(this, "Using " + list[which] + ". Now tap Test the AI.", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Problem: " + e.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showAiHealth() {
        showInfoDialog("AI health & quota", Nim.status(this), "REFRESH") { showAiHealth() }
    }

    private fun testAi() {
        Toast.makeText(this, "Testing...", Toast.LENGTH_SHORT).show()
        Thread {
            val sb = StringBuilder()
            for (vision in listOf(false, true)) {
                val p = Prefs.provider(this, vision)
                val name = if (vision) "Screen vision" else "Chat"
                try {
                    val m = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the single word: ok"))
                    val r = Nim(this).chat(m, vision, 10, 0.0)
                    sb.append("$name: works.\n  Model: ${Prefs.model(this, p, vision)}\n  Said: ${r.take(30)}\n\n")
                } catch (e: Exception) {
                    sb.append("$name: FAILED.\n  ${e.message}\n\n")
                }
            }
            runOnUiThread {
                showInfoDialog("AI test result", sb.toString().trim())
                refresh()
            }
        }.start()
    }

    private fun testVoice() {
        val v = Voice(this)
        val n = Prefs.userName(this)
        // the speech engine needs a moment to wake up
        window.decorView.postDelayed({
            v.speak("Hello" + (if (n.isEmpty()) "" else " $n") + ". I am Orion, at your service.") {
                runOnUiThread { v.shutdown() }
            }
        }, 900)
        Toast.makeText(this, "Speaking...", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ name and memory

    private fun routinesDialog() {
        val names = MacroStore.names(this)
        if (names.isEmpty()) {
            showInfoDialog("No routines yet", "Ask Orion to complete a task, then say: save this as a routine called <name>. Saved routines can replay steps without asking the AI each time.")
            return
        }
        showChoiceDialog("My routines (${names.size})", "Choose a routine to remove. To run it, tell Orion: run <name>.", names,
            { which ->
                val n = names[which]
                AlertDialog.Builder(this)
                    .setTitle("Delete \"" + n + "\"?")
                    .setMessage("Say \"run " + n + "\" to Orion to play it.")
                    .setPositiveButton("Delete") { _, _ ->
                        MacroStore.delete(this, n)
                        refresh()
                        routinesDialog()
                    }
                    .setNegativeButton("Keep") { _, _ -> routinesDialog() }
                    .show()
            },
            { refresh() }
        )
    }

    private fun skillsDialog() {
        val skills = SkillRegistry.list(this)
        val message = if (skills.isEmpty()) {
            "Create a small declarative skill using existing tools. Skills are disabled until you enable them and cannot download or execute code."
        } else {
            "Choose a skill to inspect, enable, disable, or remove. Each tool call is checked against its allowlist and budget."
        }
        if (skills.isEmpty()) {
            showChoiceDialog("Skill studio · 0 skills", message, listOf("Create skill · Define a skill using supported tools", "Skill templates · Start from a reviewed template"),
                { which -> if (which == 0) addSkillDialog() else skillTemplateDialog() })
            return
        }
        showChoiceDialog("Skill studio · ${skills.size} skills", message,
            skills.map { it.id + " · " + if (it.enabled) "ENABLED" else "DISABLED" } + listOf("Create skill · Add a custom skill", "Skill templates · Browse reviewed starters"),
            { which ->
                if (which < skills.size) skillOptions(skills[which])
                else if (which == skills.size) addSkillDialog() else skillTemplateDialog()
            })
    }

    /** Creates reviewed starter skills from existing tools only; every generated skill starts disabled. */
    private fun skillTemplateDialog() {
        val templates = listOf(
            PhoneAiSkill("web_research", "1.0.0", "Research a public topic, read a public page, and report source URLs.",
                setOf("web_search", "research_web", "fetch_url"), maxCalls = 6, maxDurationMs = 30_000L),
            PhoneAiSkill("browser_operator", "1.0.0", "Navigate public websites with visible screen checks, wait for page elements, and stop before sign-in, payment, posting, or account changes.",
                setOf("open_app", "open_url", "web_search", "read_screen", "click_text", "scroll", "wait_for", "research_web"),
                maxCalls = 8, maxDurationMs = 45_000L, requiresApproval = true),
            PhoneAiSkill("device_brief", "1.0.0", "Summarize time, battery, and basic device information.",
                setOf("time", "battery", "device_info"), maxCalls = 4, maxDurationMs = 15_000L),
            PhoneAiSkill("crypto_snapshot", "1.0.0", "Read a public Bybit spot ticker and explain that it is market data, not a trade signal.",
                setOf("bybit_public_ticker", "time"), maxCalls = 3, maxDurationMs = 15_000L),
            PhoneAiSkill("memory_review", "1.0.0", "Recall relevant saved context and optionally save a user-approved summary.",
                setOf("recall", "remember"), maxCalls = 4, maxDurationMs = 20_000L)
        )
        showChoiceDialog("Reviewed skill templates", "Select a starter. Each skill is created disabled and can only use its listed existing tools.",
            templates.map { it.id + " · " + it.description },
            { which ->
                val candidate = templates[which]
                val result = SkillRegistry.register(this, candidate)
                Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                if (result.allowed) {
                    refresh()
                    showInfoDialog("Skill created disabled", candidate.description + "\n\nAllowed tools: " +
                        candidate.allowedTools.sorted().joinToString(", ") +
                        "\nBudget: " + candidate.maxCalls + " calls / " +
                        (candidate.maxDurationMs / 1000L) + " seconds. Review it before enabling.",
                        "REVIEW SKILL") {
                        SkillRegistry.list(this).firstOrNull { it.id == candidate.id }?.let { skillOptions(it) }
                    }
                }
            })
    }

    private fun skillOptions(skill: PhoneAiSkill) {
        showChoiceDialog(skill.id + " · v" + skill.version,
            skill.description + "\n\nAllowed tools: " + skill.allowedTools.sorted().joinToString(", ") +
                "\nBudget: " + skill.maxCalls + " calls / " + (skill.maxDurationMs / 1000L) + " seconds",
            listOf(if (skill.enabled) "Disable skill · Stop this skill from running" else "Enable skill · Allow this reviewed skill to run", "Delete skill · Remove its local definition"),
            { which ->
                if (which == 0) {
                    val result = SkillRegistry.setEnabled(this, skill.id, !skill.enabled)
                    Toast.makeText(this, result.reason, Toast.LENGTH_SHORT).show()
                    refresh()
                    skillsDialog()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Delete skill " + skill.id + "?")
                        .setMessage("This removes its local definition. It cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            val result = SkillRegistry.remove(this, skill.id)
                            Toast.makeText(this, result.reason, Toast.LENGTH_SHORT).show()
                            refresh()
                            skillsDialog()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            })
    }

    private fun addSkillDialog() {
        val idBox = EditText(this).apply { hint = "Skill ID, e.g. market_brief"; setSingleLine(true) }
        val descBox = EditText(this).apply { hint = "What should this skill do?"; minLines = 2 }
        val toolsBox = EditText(this).apply {
            hint = "Allowed tools, comma-separated"
            setText("web_search,fetch_url,recall")
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(idBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(descBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(toolsBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text("Supported tools include: time, battery, device_info, list_apps, read_screen, web_search, fetch_url, research_web, github_public, bybit_public_ticker, integration_status, telegram_status, mcp_status, mcp_list_tools, open_url, recall, remember, wait, wait_for, click_text, scroll, open_app, task_list, task_add, task_update, task_delete. Only existing tools can be used; skills cannot add code or bypass approvals.", 11f, Theme.MUTED))
        }
        AlertDialog.Builder(this)
            .setTitle("Create a declarative skill")
            .setMessage("Use a short ID and only supported tool names. New skills are saved disabled.")
            .setView(wrap)
            .setPositiveButton("Save skill") { _, _ ->
                val allowed = toolsBox.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                val candidate = PhoneAiSkill(
                    id = idBox.text.toString().trim().lowercase(Locale.ROOT),
                    version = "1.0.0",
                    description = descBox.text.toString().trim(),
                    allowedTools = allowed,
                    maxCalls = 6,
                    maxDurationMs = 30_000L,
                    requiresApproval = false,
                    enabled = false
                )
                val result = SkillRegistry.register(this, candidate)
                Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                if (result.allowed) {
                    refresh()
                    AlertDialog.Builder(this)
                        .setTitle("Enable " + candidate.id + "?")
                        .setMessage("Enable it only if the purpose and tool allowlist look right.")
                        .setPositiveButton("Enable") { _, _ ->
                            val enabled = SkillRegistry.setEnabled(this, candidate.id, true)
                            Toast.makeText(this, enabled.reason, Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                        .setNegativeButton("Keep disabled", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun avatarDialog() {
        showChoiceDialog("Pick Orion's look", "Choose the visual identity shown in the assistant panel.",
            listOf("Orion · Robot head", "Nova · Friendly face", "Atlas · Tactical visor", "Lumen · Luminous guide"),
            { which ->
                Prefs.setAvatar(this, which)
                refresh()
                Toast.makeText(this, "Saved. Close and reopen the Orion panel to see it.", Toast.LENGTH_LONG).show()
            })
    }

    private fun characterDialog() {
        val profiles = CharacterProfile.all
        val current = Prefs.characterProfile(this)
        val descriptions = mapOf(
            "orion" to "Calm, sharp and quietly witty. Your everyday co-pilot.",
            "nova" to "Warm, attentive and encouraging.",
            "atlas" to "Direct, decisive and focused on action.",
            "lumen" to "Patient explanations without judgment.",
            "cipher" to "Evidence, alternatives and clear uncertainty.",
            "tech" to "Technical specialist for code, Android and debugging.",
            "muse" to "Imaginative concepts and creative options."
        )
        val glyphs = mapOf("orion" to "◈", "nova" to "✳", "atlas" to "➤", "lumen" to "✧",
            "cipher" to "⌘", "tech" to "⌬", "muse" to "✦")
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF07110F.toInt(), 0xFF0A1D19.toInt(), 0xFF06100F.toInt()))
        }
        outer.addView(text("ORION  /  RESPONSE STYLE", 10f, Theme.CYAN, true).apply {
            letterSpacing = .12f; setPadding(0, 0, 0, dp(7))
        })
        outer.addView(text("Choose your personality", 22f, Theme.TEXT, true))
        outer.addView(text("Changes how Orion communicates, not the AI provider.", 12f, Theme.MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var personalityDialog: AlertDialog? = null
        profiles.forEach { profile ->
            val selected = profile.id == current
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (selected) 0xFF10392F.toInt() else 0xFF0A1715.toInt())
                    cornerRadius = dp(15).toFloat()
                    setStroke(dp(1), if (selected) Theme.CYAN else 0xFF203D37.toInt())
                }
                isClickable = true; isFocusable = true
                contentDescription = profile.label + ", " + descriptions[profile.id].orEmpty()
            }
            card.addView(text(glyphs[profile.id] ?: "◈", 19f, Theme.CYAN, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(0xFF0A4A3A.toInt()); cornerRadius = dp(11).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(11) })
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(text(profile.label.substringBefore(" — "), 14f, Theme.TEXT, true))
            details.addView(text(descriptions[profile.id].orEmpty(), 11f, Theme.MUTED).apply {
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) card.addView(text("✓", 16f, Theme.CYAN, true).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(24), dp(28)))
            card.setOnClickListener {
                Prefs.setCharacterProfile(this, profile.id)
                refresh()
                Toast.makeText(this, "Personality saved. New replies will use it.", Toast.LENGTH_SHORT).show()
                personalityDialog?.dismiss()
            }
            cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(cards, -1, -2)
        }
        val height = (resources.displayMetrics.heightPixels - dp(240)).coerceAtLeast(dp(260)).coerceAtMost(dp(500))
        outer.addView(scroll, LinearLayout.LayoutParams(-1, height))
        outer.addView(text("PERSONALITY SAVED ON THIS PHONE", 9f, Theme.MUTED, true).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(2))
        })
        val dialog = AlertDialog.Builder(this).setView(outer).setNegativeButton("CLOSE", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
    }

    private fun describeVoice(x: android.speech.tts.Voice): String {
        val q = if (x.quality >= 400) "high quality" else "standard"
        val net = if (x.isNetworkConnectionRequired) ", online" else ""
        val place = x.locale.displayCountry.ifEmpty { x.locale.displayLanguage }
        return place + " - " + q + net + " - " + x.name.split("-").takeLast(2).joinToString("-")
    }

    private fun voiceDialog() {
        val v = Voice(this)
        Toast.makeText(this, "Loading voices...", Toast.LENGTH_SHORT).show()
        window.decorView.postDelayed({
            val list = v.voiceList()
            if (list.isEmpty()) {
                Toast.makeText(
                    this, "No voices found. Install Speech Services by Google from the Play Store.", Toast.LENGTH_LONG
                ).show()
                v.shutdown()
            } else {
                val labels = ArrayList<String>()
                labels.add("Automatic · Best available")
                for (x in list) labels.add(describeVoice(x))
                showChoiceDialog("Choose Orion's voice", "Select the speech voice used for spoken replies.", labels,
                    { which ->
                        Prefs.setVoiceName(this, if (which == 0) "" else list[which - 1].name)
                        v.refreshVoice()
                        v.speak("Hello. This is how I sound.") { runOnUiThread { v.shutdown() } }
                    },
                    { v.shutdown() })
            }
        }, 1000)
    }

    private fun localDialog(p: Provider) {
        val on = Prefs.hasKey(this, p.id)
        AlertDialog.Builder(this)
            .setTitle("Local AI on this phone")
            .setMessage(
                "Free, unlimited and private. It runs inside Termux, so it is slower and best for plain chat.\n\n" +
                    "Set it up once in Termux:\n" +
                    "1. pkg update && pkg install ollama\n" +
                    "2. ollama serve &\n" +
                    "3. ollama pull llama3.2:3b   (or llama3.2:1b for faster)\n\n" +
                    "Keep Termux open in the background, then tap Turn on here.\n\nNow: " + (if (on) "ON" else "off")
            )
            .setPositiveButton("Turn on") { _, _ ->
                Prefs.setKey(this, p.id, "on")
                Prefs.setProvider(this, false, p.id)
                refresh()
                Toast.makeText(this, "Local AI is now the chat AI. Tap Test the AI.", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Turn off") { _, _ ->
                Prefs.setKey(this, p.id, "")
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun nameDialog() {
        val box = EditText(this)
        box.hint = "Your name"
        box.setSingleLine(true)
        box.setText(Prefs.userName(this))
        val wrap = LinearLayout(this)
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("What should Orion call you?")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setUserName(this, box.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun when2(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))

    private fun memoryDialog() {
        val items = MemoryStore.all(this).sortedByDescending { it.updated }
        if (items.isEmpty()) {
            showChoiceDialog("Orion's memory (0)", "Nothing saved yet. Tell Orion things like “remember I prefer short answers”, or add a note here.",
                listOf("Add a memory · Save a preference, fact, or project note"),
                { editMemory(null) },
                { refresh() })
            return
        }
        showChoiceDialog("Orion's memory (${items.size})", "Select a memory to edit. Entries are stored locally on this phone.",
            items.map { "(" + it.category + ") · " + it.text + "  ·  " + when2(it.updated) } +
                listOf("Add memory · Save a new note", "Clear all memories · Permanently delete saved entries"),
            { which ->
                when {
                    which < items.size -> editMemory(items[which])
                    which == items.size -> editMemory(null)
                    else -> AlertDialog.Builder(this)
                        .setTitle("Delete all memories?")
                        .setMessage("Orion will forget everything it saved. This cannot be undone.")
                        .setPositiveButton("Delete all") { _, _ ->
                            MemoryStore.clear(this)
                            refresh()
                            memoryDialog()
                        }
                        .setNegativeButton("Cancel") { _, _ -> memoryDialog() }
                        .show()
                }
            },
            { refresh() })
    }

    private fun editMemory(m: Memory?) {
        val box = EditText(this).apply {
            hint = "What should Orion remember?"
            setText(m?.text ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            setTextColor(Theme.TEXT)
        }
        var selectedCategory = MemoryStore.CATEGORIES.indexOf(m?.category ?: "fact").coerceAtLeast(0)
        lateinit var categoryButton: Button
        categoryButton = button("CATEGORY  ·  " + MemoryStore.CATEGORIES[selectedCategory].uppercase(Locale.ROOT), false) {
            showChoiceDialog("Memory category", "Choose how Orion should classify this note.",
                MemoryStore.CATEGORIES.map { it + " ·  Organize saved context" },
                { which ->
                    selectedCategory = which
                    categoryButton.text = "CATEGORY  ·  " + MemoryStore.CATEGORIES[selectedCategory].uppercase(Locale.ROOT)
                })
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF07110F.toInt(), 0xFF0A1D19.toInt()))
            addView(text("ORION  /  MEMORY", 10f, Theme.CYAN, true).apply { letterSpacing = .1f; setPadding(0, 0, 0, dp(8)) })
            addView(text(if (m == null) "Add a memory" else "Edit memory", 20f, Theme.TEXT, true))
            addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(categoryButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val dialog = AlertDialog.Builder(this).setView(wrap)
            .setPositiveButton("SAVE MEMORY") { _, _ ->
                val cat = MemoryStore.CATEGORIES[selectedCategory]
                val t = box.text.toString()
                if (m == null) MemoryStore.add(this, cat, t) else MemoryStore.update(this, m.id, cat, t)
                refresh()
                memoryDialog()
            }
            .setNegativeButton("CANCEL") { _, _ -> memoryDialog() }
            .create()
        if (m != null) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "DELETE") { _, _ ->
            MemoryStore.delete(this, m.id)
            refresh()
            memoryDialog()
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(460)), -2)
    }

    private fun telegramDialog() {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        val token = EditText(this)
        token.hint = "BotFather token (stored encrypted)"
        token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        token.setSingleLine(true)
        val chat = EditText(this)
        chat.hint = "Target chat ID or @channel username"
        chat.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        chat.setSingleLine(true)
        wrap.addView(token)
        wrap.addView(chat)
        AlertDialog.Builder(this)
            .setTitle("Telegram bot setup")
            .setMessage("Create a bot with @BotFather. Add the bot to your chat and obtain the target chat ID. The token is encrypted on this device and is never included in Orion prompts. Leave neither field blank.")
            .setView(wrap)
            .setPositiveButton("Save securely") { _, _ ->
                val result = TelegramBridge.save(this, token.text.toString(), chat.text.toString())
                showInfoDialog("Telegram setup", result)
            }
            .setNeutralButton("Disconnect") { _, _ ->
                TelegramBridge.clear(this)
                Toast.makeText(this, "Telegram settings cleared from this device.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }



    private fun mcpDialog() {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        val endpoint = EditText(this)
        endpoint.hint = "MCP endpoint URL (HTTPS)"
        endpoint.setText(McpConnector.endpoint(this))
        endpoint.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        endpoint.setSingleLine(true)
        val bearer = EditText(this)
        bearer.hint = "Optional bearer token (encrypted)"
        bearer.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        bearer.setSingleLine(true)
        wrap.addView(endpoint)
        wrap.addView(bearer)
        AlertDialog.Builder(this).setTitle("Remote MCP setup")
            .setMessage("Use only an MCP server you trust. This first version only discovers tools; it does not execute remote tools. Use HTTPS; HTTP is allowed only for localhost. Credentials are encrypted with Android Keystore.")
            .setView(wrap)
            .setPositiveButton("Save server") { _, _ ->
                val result = McpConnector.save(this, endpoint.text.toString(), bearer.text.toString())
                showInfoDialog("MCP setup", result)
            }
            .setNeutralButton("Disconnect") { _, _ ->
                McpConnector.clear(this)
                Toast.makeText(this, "MCP server settings cleared.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null).show()
    }


}
