package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF21E1E1E.toInt(), 0xF0101010.toInt())).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x55464646) }
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
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xEA1B1B1B.toInt(), 0xEA101010.toInt())).apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), 0x333A3A3A)
                }
                isClickable = true; isFocusable = true; contentDescription = label
            }
            row.addView(text(pieces[0], 14f, Theme.TEXT, true))
            if (pieces.size > 1) row.addView(text(pieces[1], 11f, Theme.MUTED).apply { setPadding(0, dp(4), 0, 0) })
            row.setOnClickListener { sheet?.dismiss(); onPick(index) }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
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
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(8)); background = GradientDrawable().apply { setColor(Theme.BG); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Theme.LINE) } }
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
        b.maxLines = 2
        b.ellipsize = android.text.TextUtils.TruncateAt.END
        b.minWidth = 0
        b.setPadding(dp(8), dp(8), dp(8), dp(8))
        b.setTextColor(if (primary) Theme.ON_ACCENT else Theme.TEXT)
        val g = if (primary) {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFD8D8D8.toInt(), 0xFFBFBFBF.toInt()))
        } else {
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF0202020.toInt(), 0xF0101010.toInt()))
        }
        g.cornerRadius = dp(10).toFloat()
        g.setStroke(dp(1), if (primary) 0xFFE5E5E5.toInt() else 0x55484848)
        b.background = g
        b.elevation = 0f
        b.stateListAnimator = null
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
        val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xF0202020.toInt(), 0xF0141414.toInt(), 0xF0101010.toInt()))
        g.cornerRadius = dp(20).toFloat()
        g.setStroke(dp(1), 0x55464646)
        c.background = g
        c.setPadding(dp(16), dp(15), dp(16), dp(16))
        c.elevation = dp(1).toFloat()
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF303030.toInt(), 0xFF141414.toInt())).apply {
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), 0x55484848)
            }
            addView(OrionIconView(this@SettingsActivity).apply {
                icon = settingsIcon(title)
                tint = Theme.CYAN
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(17), dp(17)))
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
        heading.addView(text(title, 16f, Theme.TEXT, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(heading)
        if (note != null) {
            val n = text(note, 13f, Theme.MUTED).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            }
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

    /** Compact horizontal shortcuts keep the long control room easy to navigate on phones. */
    private fun quickJump(label: String, target: View, scroll: ScrollView): TextView {
        return text(label, 10f, Theme.TEXT, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .06f
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF0202020.toInt(), 0xF0141414.toInt())
            ).apply {
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), 0x55484848)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { scroll.post { scroll.smoothScrollTo(0, (target.top - dp(16)).coerceAtLeast(0)) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val sv = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(18), dp(18), dp(30))
        sv.addView(col)
        setContentView(sv)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(20), dp(19), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF242424.toInt(), 0xFF141414.toInt(), 0xFF121212.toInt())).apply {
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), 0x66464646)
            }
        }
        val headerTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brandMark = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF454545.toInt(), 0xFF252525.toInt())).apply {
                cornerRadius = dp(15).toFloat(); setStroke(dp(1), 0x666B6B6B)
            }
            addView(OrionIconView(this@SettingsActivity).apply {
                icon = "spark"; tint = Theme.CYAN; contentDescription = null
            }, LinearLayout.LayoutParams(dp(25), dp(25)))
        }
        headerTop.addView(brandMark, LinearLayout.LayoutParams(dp(46), dp(46)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(text("ORION CONTROL", 10f, Theme.CYAN, true).apply { letterSpacing = .16f })
            addView(text("Personal AI", 17f, Theme.TEXT, true).apply { setPadding(0, dp(3), 0, 0) })
        }
        headerTop.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        headerTop.addView(OrionIconView(this).apply {
            icon = "settings"; tint = Theme.MUTED; contentDescription = "Settings"
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        header.addView(headerTop)
        header.addView(text("Make Orion work\nyour way.", 29f, Theme.TEXT, true).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(0, dp(22), 0, dp(8))
        })
        header.addView(text("Tune intelligence, voice, memory and device access from one place.", 13f, Theme.MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        add(col, header, 0)
        val sectionLabel = text("PERSONALISE YOUR SYSTEM", 10f, Theme.MUTED, true).apply {
            letterSpacing = .15f
            setPadding(dp(2), dp(16), dp(2), dp(1))
        }
        add(col, sectionLabel, 5)
        add(col, button("‹  Back to Orion", false) { finish() }, 8)
        
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
        add(s1, button("Enable Orion Private Keyboard", false) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }, 8)
        add(s1, button("Select Orion Private Keyboard", false) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        }, 8)
        add(s1, text(
            "Privacy: Orion's keyboard sends each key directly to the focused field. It never reads surrounding text, selected text, or the field's existing contents. Enable it in Android settings, then select it from the keyboard picker.",
            12f, Theme.MUTED
        ), 8)
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

        // ---- 2. permission health and updates
        val updateCard = card(col, "Orion health & updates",
            "Check required permissions and see whether a newer public GitHub release is available.")
        val installedVersion = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" } catch (_: Exception) { "unknown" }
        val updateStatus = text("Installed version: $installedVersion\nUpdate status: not checked yet", 12f, Theme.MUTED)
        updateStatus.typeface = Typeface.MONOSPACE
        add(updateCard, updateStatus, 8)
        add(updateCard, button("Refresh permission health", false) {
            refresh()
            Toast.makeText(this, "Permission health refreshed.", Toast.LENGTH_SHORT).show()
        }, 8)
        add(updateCard, button("Check for Orion updates", true) { checkForUpdates(updateStatus) }, 8)
        add(updateCard, button("Open Orion releases", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Kingijimzy/phoneai/releases")))
        }, 8)

        // ---- 3. AI services
        val s2 = card(
            col, "AI providers & models",
            "Orion uses your selected primary provider first and only falls back when a request is rate-limited or the provider is unavailable. A shared per-command request budget and cooldowns prevent rapid key cycling. Simple phone actions (open apps, flashlight, volume, alarms) need no AI request."
        )
        add(s2, button("Get a free Gemini key", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }, 10)
        add(s2, button("Get an NVIDIA API key", false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://build.nvidia.com/settings")))
        }, 8)
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
        switchRow(s3, "Wake word: say \"Orion\" (experimental; uses Android speech service and battery)", Prefs.wake(this)) {
            val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (it && !hasMic) {
                Prefs.setWake(this, false)
                PhoneAiService.instance?.updateWakeWord(false)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 74)
                Toast.makeText(this, "Allow microphone access, then enable wake word again.", Toast.LENGTH_LONG).show()
            } else {
                Prefs.setWake(this, it)
                PhoneAiService.instance?.updateWakeWord(it)
                Toast.makeText(
                    this,
                    if (it) "Wake word enabled. Say “Orion” followed by your command." else "Wake word disabled.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        add(s3, text(
            "Wake detection uses Android's speech-recognition service in short sessions; it is not an offline, always-on keyword detector. It pauses while chat or live voice owns the microphone and backs off when the speech service fails.",
            12f, Theme.MUTED
        ), 8)
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
        add(s7, button(
            if (TelegramListenerService.isEnabled(this)) "Stop continuous Telegram replies" else "Start continuous Telegram replies",
            true
        ) {
            if (TelegramListenerService.isEnabled(this)) {
                TelegramListenerService.stop(this)
                showInfoDialog("Telegram listener", "Continuous replies stopped. Orion will no longer poll Telegram in the background.")
            } else {
                val started = TelegramListenerService.start(this)
                showInfoDialog(
                    "Telegram listener",
                    if (started) "Continuous Telegram replies started. Keep the ongoing Orion notification visible. Orion checks for messages about every 3 seconds while Android allows the service to run. AI replies use a short timeout and prioritize faster saved providers. Send /start to test."
                    else "Could not start the listener. First configure a valid bot token and numeric personal chat ID, then try again."
                )
            }
            recreate()
        }, 8)
        add(s7, button("Check inbox & reply once", false) {
            Toast.makeText(this, "Checking saved Telegram chat...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = TelegramBridge.checkUpdates(this)
                runOnUiThread {
                    showInfoDialog("Telegram inbox / replies", result)
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

        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            "AI" to s2,
            "VOICE" to s3,
            "MEMORY" to s4,
            "BROWSER" to browser,
            "TOOLS" to s6,
            "INTEGRATIONS" to s7
        ).forEach { (label, target) ->
            shortcuts.addView(quickJump(label, target, sv),
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(7) })
        }
        val shortcutScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(2), 0, dp(3))
            addView(shortcuts, ViewGroup.LayoutParams(-2, -2))
        }
        col.addView(shortcutScroll, 2, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
            bottomMargin = dp(2)
        })

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
        val accessibilityEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName, ignoreCase = true) == true
        val acc = if (accessibilityEnabled) "enabled" else "disabled"
        val mic = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "allowed" else "not allowed"
        val notifications = if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) "allowed" else "not allowed"
        val contacts = if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) "allowed" else "not allowed"
        val enabledKeyboard = Settings.Secure.getString(contentResolver, "enabled_input_methods")
            ?.contains(packageName, ignoreCase = true) == true
        val selectedKeyboard = Settings.Secure.getString(contentResolver, "default_input_method")
            ?.contains(packageName, ignoreCase = true) == true
        val notificationListener = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName, ignoreCase = true) == true
        val sb = StringBuilder()
        sb.append("Accessibility: $acc\\n")
        sb.append("Microphone:    $mic\\n")
        sb.append("Notifications: $notifications\\n")
        sb.append("Contacts:      $contacts\\n")
        sb.append("Private keyboard: ").append(if (selectedKeyboard) "selected" else if (enabledKeyboard) "enabled, not selected" else "disabled").append("\\n")
        sb.append("Notification access: ").append(if (notificationListener) "enabled" else "disabled").append("\\n")
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

    private fun checkForUpdates(target: TextView) {
        val installed = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" } catch (_: Exception) { "unknown" }
        target.text = "Installed version: $installed\\nUpdate status: checking latest release…"
        Thread {
            var connection: HttpURLConnection? = null
            val message = try {
                connection = (URL("https://api.github.com/repos/Kingijimzy/phoneai/releases/latest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Orion-Android-App")
                }
                if (connection.responseCode !in 200..299) {
                    "Installed version: $installed\\nUpdate status: unable to check (HTTP ${connection.responseCode})."
                } else {
                    val payload = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val tag = payload.optString("tag_name").ifBlank { "unknown" }
                    val normalizedInstalled = installed.removePrefix("v")
                    val normalizedLatest = tag.removePrefix("v")
                    if (normalizedLatest != normalizedInstalled && tag != "unknown")
                        "Installed version: $installed\\nLatest release: $tag\\nA release differs from your installed version. Review the release page before installing."
                    else
                        "Installed version: $installed\\nLatest release: $tag\\nYou appear to be on the latest tagged release."
                }
            } catch (_: Exception) {
                "Installed version: $installed\\nUpdate status: check failed. Check your connection or open Releases manually."
            } finally {
                connection?.disconnect()
            }
            runOnUiThread { target.text = message }
        }.start()
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF21E1E1E.toInt(), 0xF0101010.toInt())).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x55484848)
            }
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
        val routeIsVision = title == "Screen-vision AI"
        val routeIsChat = title == "Chat AI provider"
        var pickerDialog: AlertDialog? = null
        providers.forEach { provider ->
            val saved = Prefs.hasKey(this, provider.id)
            val selected = when {
                routeIsVision -> Prefs.provider(this, true).id == provider.id
                routeIsChat -> Prefs.provider(this, false).id == provider.id
                else -> Prefs.provider(this, false).id == provider.id ||
                    Prefs.provider(this, true).id == provider.id
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(11), dp(11))
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        if (selected) 0xFF222222.toInt() else if (saved) 0xFF1C1C1C.toInt() else 0xE61A1A1A.toInt(),
                        if (selected) 0xFF181818.toInt() else 0xE8141414.toInt()
                    )).apply {
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), if (selected) Theme.CYAN else if (saved) 0xFF363636.toInt() else 0x33383838)
                }
                isClickable = true
                isFocusable = true
                contentDescription = provider.label
            }
            val iconName = when (provider.id) {
                "gemini", "openai", "nvidia", "openrouter" -> "spark"
                "local" -> "tools"
                "anthropic", "deepseek" -> "memory"
                "groq", "cerebras", "mistral", "sambanova" -> "network"
                "together", "fireworks", "huggingface" -> "network"
                else -> "settings"
            }
            val icon = OrionIconView(this).apply {
                icon = iconName
                tint = if (saved || selected) Theme.CYAN else Theme.MUTED
                background = GradientDrawable().apply {
                    setColor(if (saved || selected) 0xFF303030.toInt() else 0xFF202020.toInt())
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(1), if (selected) 0xFF737373.toInt() else 0x44464646)
                }
                contentDescription = null
            }
            card.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(11) })
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(text(provider.label, 14f, Theme.TEXT, true))
            val state = when {
                provider.noKey && saved -> "LOCAL SERVICE · AVAILABLE"
                provider.noKey -> "LOCAL SERVICE · NEEDS SETUP"
                saved -> "KEY SAVED · CONNECTION NOT VERIFIED"
                else -> "KEY NOT ADDED"
            }
            details.addView(text(state, 10f, if (saved) Theme.CYAN else Theme.MUTED, true).apply {
                setPadding(0, dp(4), 0, 0)
            })
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) card.addView(text("ACTIVE", 8f, Theme.CYAN, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = .04f
            }, LinearLayout.LayoutParams(dp(42), dp(30)))
            card.setOnClickListener {
                pickerDialog?.dismiss()
                onPick(provider)
            }
            cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(cards, -1, -2)
        }
        val height = (resources.displayMetrics.heightPixels - dp(240)).coerceAtLeast(dp(260)).coerceAtMost(dp(500))
        outer.addView(scroll, LinearLayout.LayoutParams(-1, height))
        val dialog = AlertDialog.Builder(this).setView(outer).setNegativeButton("CLOSE", null).create()
        pickerDialog = dialog
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
            background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Theme.LINE) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF21E1E1E.toInt(), 0xF0101010.toInt())).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x55484848)
            }
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
                // NVIDIA's catalogue includes embeddings, rerankers, OCR, speech and other
                // task-specific endpoints that are not chat-completion models. Do not present
                // those as selectable chat models; many return 404/400 or empty content.
                val chatOnly = listOf(
                    "embed", "embedding", "rerank", "retriev", "ocr", "whisper", "parakeet",
                    "tts", "asr", "translat", "moderation", "content-safety", "nemoguard",
                    "parse", "clip", "segment", "detect", "diffusion", "image-generation",
                    "video-generation", "pose-estimation", "classifier"
                )
                // Live-preview models require Gemini's Live API and cannot use this app's standard image payload.
                val eligible = all.filter { id ->
                    chatOnly.none { id.lowercase().contains(it) } &&
                        !id.lowercase().contains("live")
                }
                val nvidiaPreferred = listOf(
                    "deepseek-v4.1-flash", "nemotron-3.5-lightning", "nemotron-3.5-super-vl",
                    "llama-3.3-70b", "llama-4", "qwen"
                )
                val ordered = when {
                    p.id == "nvidia" -> eligible.sortedWith(
                        compareBy<String> { id ->
                            if (vision) {
                                when {
                                    id.lowercase().contains("nemotron-3.5-super-vl") -> 0
                                    nim.isVision(id) -> 1
                                    else -> 999
                                }
                            } else {
                                nvidiaPreferred.indexOfFirst { id.lowercase().contains(it) }
                                    .let { if (it < 0) 999 else it }
                            }
                        }.thenBy { it.lowercase() }
                    )
                    vision -> eligible.sortedBy { if (nim.isVision(it)) 0 else 1 }
                    else -> eligible
                }
                // A /models catalogue only proves that an ID is listed, not that this key can
                // call it. Probe every eligible candidate with the provider's real chat endpoint
                // before exposing it in the picker. Keep failures hidden from normal selection.
                val candidates = if (p.id == "nvidia" && vision) ordered.filter { nim.isVision(it) } else ordered
                runOnUiThread {
                    Toast.makeText(this, "Live-testing ${candidates.size} models from ${p.label}. This may take a moment.", Toast.LENGTH_LONG).show()
                }
                val working = ArrayList<String>()
                val failures = ArrayList<String>()
                candidates.forEachIndexed { index, model ->
                    runOnUiThread {
                        Toast.makeText(this, "Testing model ${index + 1}/${candidates.size}: ${model.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
                    }
                    val problem = try { nim.testModel(p, model, vision) } catch (e: Exception) { e.message ?: "Probe failed" }
                    if (problem.isBlank()) working.add(model)
                    else failures.add(model.substringAfterLast('/') + ": " + problem.take(120))
                }
                runOnUiThread {
                    if (working.isEmpty()) {
                        val details = failures.take(8).joinToString("\n")
                        showInfoDialog(
                            "No verified models",
                            "None of the ${candidates.size} listed candidates passed a live API test. Your current model setting was not changed. Check the API key, provider model access, credits, and rate limits.\n\n" + details
                        )
                    } else {
                        showChoiceDialog(
                            p.label + (if (vision) " verified vision models" else " verified chat models"),
                            "${working.size} of ${candidates.size} models passed a live request using your saved API key. Only verified models are shown. Vision probes check endpoint acceptance; confirm image understanding with a real photo.",
                            working,
                            { which ->
                                val selected = working[which]
                                // Verify again immediately before saving to catch a model that
                                // became unavailable between catalogue scan and selection.
                                Toast.makeText(this, "Rechecking " + selected + "...", Toast.LENGTH_SHORT).show()
                                Thread {
                                    val problem = nim.testModel(p, selected, vision)
                                    runOnUiThread {
                                        if (problem.isBlank()) {
                                            Prefs.setModel(this, p, vision, selected)
                                            Toast.makeText(this, "Live-tested and saved: " + selected, Toast.LENGTH_LONG).show()
                                        } else {
                                            AlertDialog.Builder(this)
                                                .setTitle("Model is no longer available")
                                                .setMessage(selected + " failed its final live test. Your current model has not changed.\n\n" + problem)
                                                .setPositiveButton("OK", null)
                                                .show()
                                        }
                                    }
                                }.start()
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
        val progressText = text(
            "Preparing a real request to the selected chat provider…",
            14f, Theme.TEXT
        ).apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
        var activeNim: Nim? = null
        val progress = AlertDialog.Builder(this)
            .setTitle("Testing Orion AI")
            .setView(progressText)
            .setNegativeButton("Cancel") { _, _ -> activeNim?.cancel() }
            .create()
        progress.setCancelable(false)
        progress.show()

        Thread {
            val sb = StringBuilder()
            for (vision in listOf(false, true)) {
                if (isFinishing) break
                val p = Prefs.provider(this, vision)
                val name = if (vision) "Vision route (real image probe)" else "Chat route"
                val nim = Nim(this)
                activeNim = nim
                nim.onStatus = { stage ->
                    runOnUiThread {
                        if (progress.isShowing) progressText.text = "$name\n$stage\n\nThis test waits for the provider response. It can take a while if a model is cold or busy."
                    }
                }
                runOnUiThread {
                    if (progress.isShowing) progressText.text =
                        "$name\nSelected provider: ${p.label}\nSelected model: ${Prefs.model(this, p, vision)}\nSending a real test request…"
                }
                try {
                    val m = if (vision) {
                        // Exercise the same image_url/base64 format used by uploaded pictures.
                        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.RED)
                        val bytes = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                        bitmap.recycle()
                        val encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
                        val parts = JSONArray()
                            .put(JSONObject().put("type", "text").put(
                                "text", "Look at the attached test image. What is its main color? Reply with one word."
                            ))
                            .put(JSONObject().put("type", "image_url").put(
                                "image_url", JSONObject()
                                    .put("url", "data:image/jpeg;base64,$encoded")
                                    .put("detail", "low")
                            ))
                        JSONArray().put(JSONObject().put("role", "user").put("content", parts))
                    } else {
                        JSONArray().put(
                            JSONObject().put("role", "user").put(
                                "content", "Reply with exactly this one word: OK"
                            )
                        )
                    }
                    val answer = nim.chat(m, vision, if (vision) 96 else 16, 0.0).trim()
                    val actual = if (vision) "vision route" else "chat route"
                    sb.append("$name: RESPONSE RECEIVED.\n")
                    sb.append("Selected: ${p.label} / ${Prefs.model(this, p, vision)}\n")
                    sb.append("Returned text: ${answer.take(160).ifBlank { "(empty response)" }}\n")
                    sb.append("Note: Orion may have switched to a saved backup provider; check AI health for the provider that actually succeeded.\n\n")
                } catch (e: Exception) {
                    sb.append("$name: FAILED.\n")
                    sb.append("Selected: ${p.label} / ${Prefs.model(this, p, vision)}\n")
                    sb.append("Reason: ${e.message ?: e.javaClass.simpleName}\n\n")
                } finally {
                    nim.onStatus = null
                }
            }
            runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                showInfoDialog("AI connection test", sb.toString().trim().ifBlank { "Test cancelled before a result was returned." })
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
                setOf("open_app", "open_url", "browser_snapshot", "browser_click", "browser_type", "browser_scroll", "browser_navigate", "browser_back", "web_search", "read_screen", "click_text", "scroll", "wait_for", "research_web"),
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
        val icons = mapOf(
            "orion" to "spark", "nova" to "voice", "atlas" to "tools",
            "lumen" to "memory", "cipher" to "shield", "tech" to "settings", "muse" to "network"
        )
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF101010.toInt(), 0xFF171717.toInt(), 0xFF0B0B0B.toInt()))
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
                    setColor(if (selected) 0xFF1B1B1B.toInt() else 0xFF121212.toInt())
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), if (selected) Theme.CYAN else 0x33383838)
                }
                isClickable = true; isFocusable = true
                contentDescription = profile.label + ", " + descriptions[profile.id].orEmpty()
            }
            card.addView(OrionIconView(this).apply {
                icon = icons[profile.id] ?: "spark"
                tint = if (selected) Theme.CYAN else Theme.MUTED
                background = GradientDrawable().apply {
                    setColor(if (selected) 0xFF3A3A3A.toInt() else 0xFF202020.toInt())
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(1), if (selected) Theme.CYAN else 0x44464646)
                }
                contentDescription = profile.label
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(11) })
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            details.addView(text(profile.label.substringBefore(" — "), 14f, Theme.TEXT, true))
            details.addView(text(descriptions[profile.id].orEmpty(), 11f, Theme.MUTED).apply {
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
            if (selected) card.addView(text("ACTIVE", 8f, Theme.CYAN, true).apply {
                gravity = Gravity.CENTER
                letterSpacing = .03f
            }, LinearLayout.LayoutParams(dp(42), dp(28)))
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
            background = GradientDrawable().apply { setColor(Theme.BG); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Theme.LINE) }
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
