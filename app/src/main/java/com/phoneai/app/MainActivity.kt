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
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var chatBtn: Button
    private lateinit var visionBtn: Button
    private lateinit var nameBtn: Button
    private lateinit var memBtn: Button
    private lateinit var avatarBtn: Button
    private lateinit var characterBtn: Button
    private lateinit var routBtn: Button
    private lateinit var skillBtn: Button
    private lateinit var topAvatar: AvatarView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        b.textSize = 15f
        b.setTextColor(if (primary) Theme.ON_ACCENT else Theme.TEXT)
        val g = GradientDrawable()
        g.setColor(if (primary) Theme.CYAN else Theme.PANEL2)
        g.cornerRadius = dp(14).toFloat()
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
        g.setStroke(dp(1), Theme.LINE)
        c.background = g
        c.setPadding(dp(16), dp(14), dp(16), dp(16))
        c.addView(text(title, 17f, Theme.CYAN, true))
        if (note != null) {
            val n = text(note, 13f, Theme.MUTED)
            n.setPadding(0, dp(4), 0, 0)
            c.addView(n)
        }
        add(parent, c, 14)
        return c
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
        val sv = ScrollView(this)
        sv.setBackgroundColor(Theme.BG)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(18), dp(40), dp(18), dp(32))
        sv.addView(col)
        setContentView(sv)

        topAvatar = AvatarView(this)
        val orbLp = LinearLayout.LayoutParams(dp(190), dp(190))
        orbLp.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(topAvatar, orbLp)
        val title = text("O R I O N   A I", 24f, Theme.TEXT, true)
        title.gravity = Gravity.CENTER
        add(col, title, 4)
        val sub = text("Your personal AI for this phone. Speak, type, and let it act.", 14f, Theme.MUTED)
        sub.gravity = Gravity.CENTER
        add(col, sub, 4)

        // ---- 1. setup
        val s1 = card(col, "1. Switch it on")
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
            col, "2. AI services",
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
        add(s2, button("Test the AI", false) { testAi() }, 10)
        add(s2, button("Choose chat model", false) { pickModel(false) }, 8)
        add(s2, button("Choose vision model", false) { pickModel(true) }, 8)

        // ---- 3. voice
        val s3 = card(col, "3. Voice")
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
                if (fromUser) Prefs.setRate(this@MainActivity, 0.5f + p / 100f)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        add(s3, bar, 2)
        avatarBtn = button("", false) { avatarDialog() }
        add(s3, avatarBtn, 10)
        characterBtn = button("", false) { characterDialog() }
        add(s3, characterBtn, 8)
        add(s3, button("Choose a voice", false) { voiceDialog() }, 8)
        add(s3, button("Hear Orion's voice", false) { testVoice() }, 8)

        // ---- 4. memory
        val s4 = card(
            col, "4. Memory",
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
            col, "5. Screen recording",
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

        // ---- 6. local device utilities
        val s6 = card(
            col, "6. Device utilities",
            "Network Radar, Game Sentinel, and user-selected file handling. These tools run locally on your phone."
        )
        add(s6, button("Open Network Radar, Game Sentinel & Files", true) {
            startActivity(Intent(this, DeviceUtilitiesActivity::class.java))
        }, 10)

        // ---- 7. external integrations
        val s7 = card(
            col, "7. Integrations & workflows",
            "Connect a Telegram bot safely, inspect connector status, and see which services are genuinely available. Bot tokens are encrypted with Android Keystore and never sent to the AI model."
        )
        add(s7, button("Configure Telegram bot", true) { telegramDialog() }, 8)
        add(s7, button("Configure remote MCP server", true) { mcpDialog() }, 8)
        add(s7, button("Discover MCP tools", false) {
            Toast.makeText(this, "Contacting MCP server...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = McpConnector.listTools(this)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("MCP tools").setMessage(result)
                        .setPositiveButton("OK", null).show()
                }
            }.start()
        }, 8)
        add(s7, button("Check Telegram inbox (read-only)", false) {
            Toast.makeText(this, "Checking saved Telegram chat...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = TelegramBridge.checkUpdates(this)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Telegram inbox").setMessage(result)
                        .setPositiveButton("OK", null).show()
                }
            }.start()
        }, 8)
        add(s7, button("Check Telegram bot connection", false) {
            Toast.makeText(this, "Checking Telegram...", Toast.LENGTH_SHORT).show()
            Thread {
                val result = TelegramBridge.status(this)
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Telegram connection").setMessage(result)
                        .setPositiveButton("OK", null).show()
                }
            }.start()
        }, 8)
        add(s7, button("Show integrations and MCP status", false) {
            AlertDialog.Builder(this).setTitle("Connector status")
                .setMessage(IntegrationStatus.summary(this))
                .setPositiveButton("OK", null).show()
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
        skillBtn.text = "Skill registry (" + SkillRegistry.list(this).count { it.enabled } + " enabled / " + SkillRegistry.list(this).size + " total)"
        topAvatar.style = Prefs.avatar(this)
        avatarBtn.text = "Avatar look: " + listOf("Orion", "Nova", "Atlas", "Lumen").getOrElse(Prefs.avatar(this)) { "Orion" } + "  (tap to change)"
    }

    // ------------------------------------------------------------ keys and providers

    /** Pick a service, then paste its key. */
    private fun keyDialog() {
        val labels = Providers.all.map { it.label + if (Prefs.hasKey(this, it.id)) (if (it.noKey) "  (on)" else "  (saved)") else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which service is the key for?")
            .setItems(labels) { _, which -> pasteKey(Providers.all[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pasteKey(p: Provider) {
        if (p.noKey) {
            localDialog(p)
            return
        }
        val box = EditText(this)
        box.hint = p.keyHint
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        box.setSingleLine(true)
        val pad = dp(20)
        val wrap = LinearLayout(this)
        wrap.setPadding(pad, dp(8), pad, 0)
        wrap.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle(p.label + " key")
            .setMessage(p.info + "\n\nLong-press the box and tap Paste.")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val k = box.text.toString().trim()
                if (k.isEmpty()) {
                    Toast.makeText(this, "Nothing pasted.", Toast.LENGTH_SHORT).show()
                } else {
                    val chatNeeds = !Prefs.hasKey(this, Prefs.provider(this, false).id)
                    val visionNeeds = !Prefs.hasKey(this, Prefs.provider(this, true).id)
                    Prefs.setKey(this, p.id, k)
                    if (chatNeeds) Prefs.setProvider(this, false, p.id)
                    if (visionNeeds && p.hasVision) Prefs.setProvider(this, true, p.id)
                    Toast.makeText(this, p.label + " key saved.", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun providerDialog(vision: Boolean) {
        val list = Providers.all.filter { !vision || it.hasVision }
        val labels = list.map { it.label + if (Prefs.hasKey(this, it.id)) "" else "  (no key yet)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (vision) "Which AI reads the screen?" else "Which AI answers chat?")
            .setItems(labels) { _, which ->
                val p = list[which]
                Prefs.setProvider(this, vision, p.id)
                if (!Prefs.hasKey(this, p.id)) {
                    Toast.makeText(this, "Add a " + p.label + " key first.", Toast.LENGTH_LONG).show()
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                        val items = list.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle(p.label + (if (vision) " vision model" else " chat model"))
                            .setItems(items) { _, which ->
                                Prefs.setModel(this, p, vision, items[which])
                                Toast.makeText(this, "Using " + items[which] + ". Now tap Test the AI.", Toast.LENGTH_LONG).show()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Problem: " + e.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
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
                AlertDialog.Builder(this)
                    .setTitle("Test result")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("OK", null)
                    .show()
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
        val b = AlertDialog.Builder(this).setTitle("My routines (" + names.size + ")")
        if (names.isEmpty()) {
            b.setMessage("No routines yet. Ask Orion to do a task, then say: save this as a routine called <name>.")
        } else {
            b.setItems(names.toTypedArray()) { _, which ->
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
            }
        }
        b.setNegativeButton("Close") { _, _ -> refresh() }
        b.show()
    }

    private fun skillsDialog() {
        val skills = SkillRegistry.list(this)
        val message = if (skills.isEmpty()) {
            "Create a small, declarative skill by naming its purpose and allowed existing tools. Skills are disabled until you enable them. They cannot download or execute code."
        } else {
            "Choose a skill to enable, disable, or remove. Run an enabled skill by saying: run skill <id>: <task>. Each tool call is checked against its allowlist and budget."
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Skill registry (" + skills.size + ")")
            .setMessage(message)
            .setPositiveButton("Add skill") { _, _ -> addSkillDialog() }
            .setNegativeButton("Close", null)
        if (skills.isNotEmpty()) {
            builder.setItems(skills.map { it.id + "  " + if (it.enabled) "ON" else "OFF" }.toTypedArray()) { _, which ->
                skillOptions(skills[which])
            }
        }
        builder.show()
    }

    private fun skillOptions(skill: PhoneAiSkill) {
        val actions = arrayOf(if (skill.enabled) "Disable skill" else "Enable skill", "Delete skill")
        AlertDialog.Builder(this)
            .setTitle(skill.id + "  v" + skill.version)
            .setMessage(skill.description + "\n\nAllowed tools: " + skill.allowedTools.sorted().joinToString(", ") +
                "\nBudget: " + skill.maxCalls + " calls / " + (skill.maxDurationMs / 1000L) + " seconds")
            .setItems(actions) { _, which ->
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
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            addView(text("Supported tools: time, battery, device_info, list_apps, read_screen, web_search, fetch_url, research_web, open_url, recall, remember, wait, wait_for, click_text, scroll, open_app", 11f, Theme.MUTED))
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
        AlertDialog.Builder(this)
            .setTitle("Pick Orion's look")
            .setItems(arrayOf("Orion (robot head)", "Nova (friendly face)", "Atlas (tactical visor)", "Lumen (luminous guide)")) { _, which ->
                Prefs.setAvatar(this, which)
                refresh()
                Toast.makeText(this, "Saved. Close and reopen the Orion panel to see it.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun characterDialog() {
        val profiles = CharacterProfile.all
        AlertDialog.Builder(this)
            .setTitle("Choose Orion's personality")
            .setMessage("This changes how Orion talks and responds, not the avatar drawing or AI provider.")
            .setItems(profiles.map { it.label }.toTypedArray()) { _, which ->
                Prefs.setCharacterProfile(this, profiles[which].id)
                refresh()
                Toast.makeText(this, "Personality saved. New replies will use it.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                labels.add("Automatic (best available)")
                for (x in list) labels.add(describeVoice(x))
                AlertDialog.Builder(this)
                    .setTitle("Choose Orion's voice")
                    .setItems(labels.toTypedArray()) { _, which ->
                        Prefs.setVoiceName(this, if (which == 0) "" else list[which - 1].name)
                        v.refreshVoice()
                        v.speak("Hello. This is how I sound.") { runOnUiThread { v.shutdown() } }
                    }
                    .setNegativeButton("Cancel") { _, _ -> v.shutdown() }
                    .setOnCancelListener { v.shutdown() }
                    .show()
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
        val b = AlertDialog.Builder(this).setTitle("Orion's memory (" + items.size + ")")
        if (items.isEmpty()) {
            b.setMessage(
                "Nothing saved yet. Tell Orion things like \"remember I prefer short answers\" " +
                    "or \"we are working on my trading agent\", or tap Add."
            )
        } else {
            val labels = items.map { "(" + it.category + ") " + it.text + "  " + when2(it.updated) }.toTypedArray()
            b.setItems(labels) { _, which -> editMemory(items[which]) }
        }
        b.setPositiveButton("Add") { _, _ -> editMemory(null) }
        b.setNegativeButton("Close") { _, _ -> refresh() }
        if (items.isNotEmpty()) {
            b.setNeutralButton("Clear all") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Delete ALL memories?")
                    .setMessage("Orion will forget everything it saved. This cannot be undone.")
                    .setPositiveButton("Delete all") { _, _ ->
                        MemoryStore.clear(this)
                        refresh()
                        memoryDialog()
                    }
                    .setNegativeButton("Cancel") { _, _ -> memoryDialog() }
                    .show()
            }
        }
        b.show()
    }

    private fun editMemory(m: Memory?) {
        val box = EditText(this)
        box.hint = "What should Orion remember?"
        box.setText(m?.text ?: "")
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        box.minLines = 2
        val spin = Spinner(this)
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MemoryStore.CATEGORIES)
        spin.setSelection(MemoryStore.CATEGORIES.indexOf(m?.category ?: "fact").coerceAtLeast(0))
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.addView(spin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val b = AlertDialog.Builder(this)
            .setTitle(if (m == null) "Add a memory" else "Edit memory")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val cat = MemoryStore.CATEGORIES[spin.selectedItemPosition]
                val t = box.text.toString()
                if (m == null) MemoryStore.add(this, cat, t) else MemoryStore.update(this, m.id, cat, t)
                refresh()
                memoryDialog()
            }
            .setNegativeButton("Cancel") { _, _ -> memoryDialog() }
        if (m != null) {
            b.setNeutralButton("Delete") { _, _ ->
                MemoryStore.delete(this, m.id)
                refresh()
                memoryDialog()
            }
        }
        b.show()
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
                AlertDialog.Builder(this).setMessage(result).setPositiveButton("OK", null).show()
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
                AlertDialog.Builder(this).setMessage(result).setPositiveButton("OK", null).show()
            }
            .setNeutralButton("Disconnect") { _, _ ->
                McpConnector.clear(this)
                Toast.makeText(this, "MCP server settings cleared.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null).show()
    }


}
