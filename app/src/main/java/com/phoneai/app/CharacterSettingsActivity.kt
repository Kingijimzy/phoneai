package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The companion identity page; presentation can change, authority and safety cannot. */
class CharacterSettingsActivity : Activity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Theme.TEXT)
        backgroundTintList = null
        background = GradientDrawable().apply {
            setColor(Theme.PANEL2)
            cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
            setStroke(dp(1), Theme.LINE_STRONG)
        }
        minHeight = dp(48)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        isFocusable = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.985f).scaleY(0.985f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
            }
            false
        }
        setOnClickListener { action() }
    }

    private lateinit var root: LinearLayout

    private fun card(title: String, body: LinearLayout.() -> Unit) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Theme.PANEL)
                cornerRadius = dp(Theme.CORNER_MEDIUM_DP).toFloat()
                setStroke(dp(1), Theme.LINE)
            }
            setPadding(dp(16), dp(15), dp(16), dp(16))
            addView(label(title, 15f, Theme.TEXT, true))
            body()
        }
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        section.alpha = 0f
        section.translationY = dp(6).toFloat()
        section.post {
            section.animate().alpha(1f).translationY(0f)
                .setDuration(Theme.MOTION_MEDIUM_MS.toLong()).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(20), dp(17), dp(28))
            setBackgroundColor(Theme.BG)
        }
        root.addView(label("ORION  /  IDENTITY", 10f, Theme.SILVER, true))
        root.addView(label("Character & philosophy", 27f, Theme.TEXT, true).apply {
            setPadding(0, dp(7), 0, dp(5))
        })
        root.addView(label(
            "Orion has one consistent identity across providers, modes and capabilities. Character changes presentation, never authority.",
            13f, Theme.MUTED
        ).apply { setPadding(0, 0, 0, dp(8)) })

        card("ORION — THE ENTITY") {
            addView(label(CharacterProfile.get(Prefs.characterProfile(this@CharacterSettingsActivity)).label, 18f, Theme.TEXT, true)
                .apply { setPadding(0, dp(9), 0, dp(5)) })
            addView(label(
                "Calm, perceptive, decisive and quietly witty. Orion thinks in systems, anticipates relevant needs without being intrusive, explains trade-offs and admits uncertainty.",
                12f, Theme.MUTED
            ))
            addView(label(
                "This identity stays stable when you change AI providers. No provider gets to redefine Orion or claim capabilities it has not demonstrated.",
                12f, Theme.MUTED
            ).apply { setPadding(0, dp(8), 0, 0) })
        }

        card("ENTITY PHILOSOPHY") {
            val principles = listOf(
                "Truth before confidence" to "Distinguish what Orion knows, infers, intends and has verified.",
                "One execution authority" to "All phone, file, browser and developer actions pass through the registered native tool dispatcher.",
                "User autonomy" to "Explain consequential actions and preserve approval, permission and cancellation boundaries.",
                "Continuity with restraint" to "Remember commitments and relevant context without inventing memories or exposing unrelated private details.",
                "Evidence over appearance" to "Show running, completed, failed and interrupted work honestly. Never report an action as complete without evidence."
            )
            principles.forEach { (heading, detail) ->
                addView(label(heading, 13f, Theme.TEXT, true).apply { setPadding(0, dp(9), 0, dp(2)) })
                addView(label(detail, 11f, Theme.MUTED))
            }
            addView(label(
                "A persona, mood or provider response can never grant permissions, bypass confirmation, weaken privacy or change trading restrictions.",
                11f, Theme.SILVER, true
            ).apply { setPadding(0, dp(12), 0, 0) })
        }

        card("APPROVED LANGUAGES") {
            val selected = Prefs.knownLanguages(this@CharacterSettingsActivity)
            addView(label(
                selected.mapNotNull { Prefs.knownLanguageLabels[it] }.joinToString(" · "),
                13f, Theme.SILVER
            ).apply { setPadding(0, dp(8), 0, dp(8)) })
            addView(button("Choose languages Orion may use") { chooseLanguages() },
                LinearLayout.LayoutParams(-1, -2))
            addView(label(
                "Orion follows this allowlist and does not switch languages unless you change it.",
                11f, Theme.MUTED
            ).apply { setPadding(0, dp(6), 0, 0) })
        }

        card("VOICE & PRESENTATION") {
            addView(label("Voice, wake word, speech recognition and live-call presentation have their own controls.", 12f, Theme.MUTED)
                .apply { setPadding(0, dp(8), 0, dp(8)) })
            addView(button("Open voice and speech settings") {
                startActivity(Intent(this@CharacterSettingsActivity, VoiceSettingsActivity::class.java))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        root.addView(button("Back to settings") { finish() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(root)
        })
    }

    private fun chooseLanguages() {
        val entries = Prefs.knownLanguageLabels.entries.toList()
        val selected = Prefs.knownLanguages(this).toMutableSet()
        val checked = entries.map { it.key in selected }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Languages Orion may use")
            .setMultiChoiceItems(entries.map { it.value }.toTypedArray(), checked) { _, which, isChecked ->
                val key = entries[which].key
                if (isChecked) selected.add(key) else selected.remove(key)
            }
            .setPositiveButton("Save") { _, _ ->
                Prefs.setKnownLanguages(this, selected)
                Toast.makeText(
                    this,
                    "Approved languages: " + Prefs.knownLanguages(this)
                        .mapNotNull { Prefs.knownLanguageLabels[it] }.joinToString(", "),
                    Toast.LENGTH_LONG
                ).show()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
