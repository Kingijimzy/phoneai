package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** UI for recording, replaying, and deleting local deterministic routines. */
class TeachModeActivity : Activity() {
    private lateinit var nameInput: EditText
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun shape(fill: Int, stroke: Int = Theme.LINE) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(14).toFloat(); setStroke(dp(1), stroke)
    }
    private fun button(s: String, action: () -> Unit) = label(s, 13f, Theme.CYAN, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(13), dp(12), dp(13), dp(12))
        background = shape(0xFF0D2822.toInt(), 0xFF167C66.toInt())
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            setBackgroundColor(Theme.BG)
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("‹", 32f, Theme.TEXT).apply {
            gravity = Gravity.CENTER; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(46)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Teach Mode", 22f, Theme.TEXT, true))
        titles.addView(label("Record once. Replay locally. No AI calls.", 12f, Theme.MUTED))
        head.addView(titles)
        root.addView(head)
        val explainer = label("Teach Orion a repeatable sequence by doing it once on your phone. Taps, long presses, swipes and waits are stored on this device. Replay is deterministic and does not use AI tokens.", 13f, Theme.MUTED)
        explainer.setPadding(dp(2), dp(14), dp(2), dp(16))
        root.addView(explainer)
        nameInput = EditText(this).apply {
            hint = "Routine name, e.g. FC daily rewards"
            setHintTextColor(0xFF80918D.toInt()); setTextColor(Theme.TEXT); textSize = 14f
            singleLine = true; background = shape(0xFF0C1715.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        root.addView(nameInput, LinearLayout.LayoutParams(-1, dp(50)))
        root.addView(button("●  START RECORDING") {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) { nameInput.error = "Name this routine first"; return@button }
            val service = PhoneAiService.instance
            if (service == null) {
                status.text = "Enable Orion in Android Accessibility settings first."
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                val result = service.startTeach(name)
                status.text = if (result == "ok") "Recording $name. Perform the sequence, then tap the red REC bar at the top to save." else result
            }
        }.apply { setPadding(dp(16), dp(14), dp(16), dp(14)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        status = label("Saved routines stay on this phone. Use replay only where repeated taps are safe.", 12f, Theme.MUTED)
        status.setPadding(dp(2), dp(12), dp(2), dp(12)); root.addView(status)
        root.addView(label("YOUR ROUTINES", 10f, Theme.CYAN, true).apply {
            letterSpacing = .12f; setPadding(0, dp(8), 0, dp(8))
        })
        val scroll = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) refresh() }

    private fun refresh() {
        list.removeAllViews()
        val names = MacroStore.names(this)
        if (names.isEmpty()) {
            list.addView(label("No routines yet. Start a recording above.", 13f, Theme.MUTED).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
            return
        }
        names.forEach { name ->
            val found = MacroStore.find(this, name)
            val count = found?.second?.length() ?: 0
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12))
                background = shape(0xFF0A1513.toInt())
            }
            card.addView(label(name, 15f, Theme.TEXT, true))
            card.addView(label("$count recorded steps · offline replay", 11f, Theme.MUTED).apply {
                setPadding(0, dp(4), 0, dp(10))
            })
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            actions.addView(button("▶ Replay") {
                val service = PhoneAiService.instance
                if (service == null) {
                    Toast.makeText(this@TeachModeActivity, "Enable Orion Accessibility first.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    status.text = "Replaying $name locally… Keep the target app in the foreground."
                    service.sendFromMainChat("run $name")
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(button("Delete") {
                if (MacroStore.delete(this@TeachModeActivity, name)) {
                    status.text = "Deleted $name"; refresh()
                } else status.text = "Could not delete this routine."
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            card.addView(actions)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        }
    }
}
