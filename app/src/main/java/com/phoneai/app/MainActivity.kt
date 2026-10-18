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

        val orb = OrbView(this)
        val orbLp = LinearLayout.LayoutParams(dp(150), dp(150))
        orbLp.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(orb, orbLp)
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
            "14 services. Best free setup: Google Gemini (sees the screen) plus Groq or Cerebras (very fast chat). " +
                "Add several keys and Orion switches automatically if one is busy."
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
    }

    // ------------------------------------------------------------ keys and providers

    /** Pick a service, then paste its key. */
    private fun keyDialog() {
        val labels = Providers.all.map { it.label + if (Prefs.hasKey(this, it.id)) "  (saved)" else "" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which service is the key for?")
            .setItems(labels) { _, which -> pasteKey(Providers.all[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pasteKey(p: Provider) {
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
}
