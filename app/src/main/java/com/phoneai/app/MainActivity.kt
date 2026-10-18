package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** The setup screen: turn things on, save keys, choose the AI, test it. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var chatBtn: Button
    private lateinit var visionBtn: Button

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
        b.textSize = 16f
        b.setTextColor(if (primary) 0xFF04150D.toInt() else 0xFFE9EEF2.toInt())
        val g = GradientDrawable()
        g.setColor(if (primary) 0xFF3DDC97.toInt() else 0xFF171C22.toInt())
        g.cornerRadius = dp(14).toFloat()
        if (!primary) g.setStroke(dp(1), 0xFF212932.toInt())
        b.background = g
        b.setOnClickListener { onClick() }
        return b
    }

    private fun add(col: LinearLayout, v: View, top: Int = 12) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(top), 0, 0)
        col.addView(v, lp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFF0B0D10.toInt())
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(48), dp(20), dp(32))
        sv.addView(col)
        setContentView(sv)

        add(col, text("Phone AI", 30f, 0xFFE9EEF2.toInt(), true), 0)
        add(col, text("An assistant that uses your phone the way you do.", 15f, 0xFF8A96A3.toInt()), 4)
        status = text("", 14f, 0xFF3DDC97.toInt())
        status.typeface = Typeface.MONOSPACE
        add(col, status, 18)

        add(col, text("1. Accessibility", 18f, 0xFFE9EEF2.toInt(), true), 24)
        add(
            col,
            text(
                "Turn on Phone AI in the list. If it says Restricted setting, open this app's App info, " +
                    "tap the three dots, choose Allow restricted settings, then try again.",
                13f, 0xFF8A96A3.toInt()
            ),
            4
        )
        add(col, button("Open Accessibility settings", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        add(col, text("2. Microphone", 18f, 0xFFE9EEF2.toInt(), true), 24)
        add(col, button("Allow microphone", false) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }, 8)

        add(col, text("3. AI service and key", 18f, 0xFFE9EEF2.toInt(), true), 24)
        add(
            col,
            text(
                "Add a key for any service (NVIDIA, Google Gemini, Groq, OpenRouter). " +
                    "Suggested: Groq for chat (fast), Gemini for reading the screen.",
                13f, 0xFF8A96A3.toInt()
            ),
            4
        )
        add(col, button("Add or change an API key", true) { keyDialog() }, 8)
        chatBtn = button("", false) { providerDialog(false) }
        add(col, chatBtn, 8)
        visionBtn = button("", false) { providerDialog(true) }
        add(col, visionBtn, 8)
        add(col, button("Test the AI", false) { testAi() }, 8)
        add(col, button("Choose model for chat", false) { pickModel(false) }, 8)
        add(col, button("Choose model for screen vision", false) { pickModel(true) }, 8)

        add(col, text("4. Voice", 18f, 0xFFE9EEF2.toInt(), true), 24)
        val voiceSw = Switch(this)
        voiceSw.text = "Speak the answers"
        voiceSw.setTextColor(0xFFE9EEF2.toInt())
        voiceSw.isChecked = Prefs.voice(this)
        voiceSw.setOnCheckedChangeListener { _, on -> Prefs.setVoice(this, on) }
        add(col, voiceSw, 8)
        val handsSw = Switch(this)
        handsSw.text = "Hands-free: listen again after each answer"
        handsSw.setTextColor(0xFFE9EEF2.toInt())
        handsSw.isChecked = Prefs.hands(this)
        handsSw.setOnCheckedChangeListener { _, on -> Prefs.setHands(this, on) }
        add(col, handsSw, 8)

        add(col, button("Open the assistant", true) {
            val s = PhoneAiService.instance
            if (s == null) {
                Toast.makeText(this, "Turn on Accessibility first (step 1).", Toast.LENGTH_LONG).show()
            } else {
                s.showPanel()
                moveTaskToBack(true)
            }
        }, 28)

        add(
            col,
            text(
                "Safety: it asks you before sending, paying, buying or deleting, and it never types " +
                    "passwords or codes. Tap Stop at any time. Free AI services may keep or read what " +
                    "you send them, so avoid private screens. Games can ban accounts that use automation.",
                12f, 0xFF8A96A3.toInt()
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
    }

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
            .setMessage("Long-press the box and tap Paste.")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val k = box.text.toString().trim()
                if (k.isEmpty()) {
                    Toast.makeText(this, "Nothing pasted.", Toast.LENGTH_SHORT).show()
                } else {
                    // If the chat or vision AI had no key yet, point it at this service.
                    val chatNeeds = !Prefs.hasKey(this, Prefs.provider(this, false).id)
                    val visionNeeds = !Prefs.hasKey(this, Prefs.provider(this, true).id)
                    Prefs.setKey(this, p.id, k)
                    if (chatNeeds) Prefs.setProvider(this, false, p.id)
                    if (visionNeeds) Prefs.setProvider(this, true, p.id)
                    Toast.makeText(this, p.label + " key saved.", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun providerDialog(vision: Boolean) {
        val labels = Providers.all.map { it.label + if (Prefs.hasKey(this, it.id)) "" else "  (no key yet)" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (vision) "Which AI reads the screen?" else "Which AI answers chat?")
            .setItems(labels) { _, which ->
                val p = Providers.all[which]
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
                    sb.append("$name: ${p.label} works.\n  Model: ${Prefs.model(this, p, vision)}\n  Said: ${r.take(30)}\n\n")
                } catch (e: Exception) {
                    sb.append("$name: ${p.label} FAILED.\n  ${e.message}\n\n")
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
}
