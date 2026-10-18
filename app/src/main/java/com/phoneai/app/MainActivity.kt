package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
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

/** The setup screen: turn things on, save the key, test it. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var keyBox: EditText

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

    private fun add(col: LinearLayout, v: android.view.View, top: Int = 12) {
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

        add(col, text("3. NVIDIA key", 18f, 0xFFE9EEF2.toInt(), true), 24)
        keyBox = EditText(this)
        keyBox.hint = "nvapi-..."
        keyBox.setHintTextColor(0xFF8A96A3.toInt())
        keyBox.setTextColor(0xFFE9EEF2.toInt())
        keyBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyBox.setSingleLine(true)
        add(col, keyBox, 8)
        add(col, button("Save key", true) {
            val k = keyBox.text.toString().trim()
            if (k.isEmpty()) {
                Toast.makeText(this, "Paste your key first.", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.setKey(this, k)
                keyBox.setText("")
                Toast.makeText(this, "Key saved.", Toast.LENGTH_SHORT).show()
                refresh()
            }
        })
        add(col, button("Test the AI", false) { testAi() }, 8)
        add(col, button("Choose AI model (chat)", false) { pickModel(false) }, 8)
        add(col, button("Choose AI model (screen vision)", false) { pickModel(true) }, 8)

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
                    "passwords or codes. Tap Stop at any time. Games can ban accounts that use automation, " +
                    "so use a spare account.",
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
        val key = if (Prefs.key(this).isNotEmpty()) "saved" else "missing"
        status.text = "Accessibility: $acc\nMicrophone:    $mic\nNVIDIA key:    $key"
        keyBox.hint = if (Prefs.key(this).isNotEmpty()) "Key saved. Paste a new one to replace it." else "nvapi-..."
    }

    /** Shows every model your key can see; tap one to use it. */
    private fun pickModel(vision: Boolean) {
        Toast.makeText(this, "Loading models...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val nim = Nim(this)
                val all = nim.allModels()
                // Show every model. Vision models come first in the vision list.
                val list = if (vision) all.sortedBy { if (nim.isVision(it)) 0 else 1 } else all
                runOnUiThread {
                    if (list.isEmpty()) {
                        Toast.makeText(this, "No models found (${all.size} total).", Toast.LENGTH_LONG).show()
                    } else {
                        val items = list.toTypedArray()
                        android.app.AlertDialog.Builder(this)
                            .setTitle(if (vision) "Pick a vision model" else "Pick a chat model")
                            .setItems(items) { _, which ->
                                if (vision) Prefs.setVisionModel(this, items[which]) else Prefs.setTextModel(this, items[which])
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
            val msg = try {
                val m = JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with the single word: ok"))
                val r = Nim(this).chat(m, false, 10, 0.0)
                "AI works. Model: " + Prefs.textModel(this) + " said: " + r.take(30)
            } catch (e: Exception) {
                "Problem: " + e.message
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }
}
