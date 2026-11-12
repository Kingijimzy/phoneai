package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.text.InputType
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
            val entityName = Prefs.orionEntityName(this@CharacterSettingsActivity)
            val entityDescription = Prefs.orionEntityDescription(this@CharacterSettingsActivity)
            addView(label(entityName, 20f, Theme.TEXT, true).apply { setPadding(0, dp(9), 0, dp(5)) })
            addView(label(
                entityDescription.ifBlank { "A calm, perceptive, decisive companion. Define the identity and purpose in your own words." },
                12f, Theme.MUTED
            ))
            addView(label(
                "The identity follows you across providers and modes. Editing it changes the companion's name and conversational style, not permissions or capabilities.",
                11f, Theme.SILVER
            ).apply { setPadding(0, dp(8), 0, dp(8)) })
            addView(button("Edit entity name") { editIdentityField(
                "Entity name", Prefs.orionEntityName(this@CharacterSettingsActivity), 40, false,
                Prefs::setOrionEntityName
            ) })
            addView(button("Edit entity description") { editIdentityField(
                "Entity description", Prefs.orionEntityDescription(this@CharacterSettingsActivity), 1200, true,
                Prefs::setOrionEntityDescription
            ) }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) } })
        }

        card("ENTITY PHILOSOPHY") {
            val principles = listOf(
                "Truth before confidence" to "Distinguish observation, inference, intention and verified result.",
                "User autonomy" to "The owner can correct, pause, cancel and change direction.",
                "Evidence over appearance" to "Never report an action as complete without evidence.",
                "Minimum sufficient action" to "Choose the smallest reliable intervention and verify its outcome.",
                "Continuity with restraint" to "Remember relevant commitments without inventing memories or exposing unrelated context."
            )
            principles.forEach { (heading, detail) ->
                addView(label(heading, 13f, Theme.TEXT, true).apply { setPadding(0, dp(9), 0, dp(2)) })
                addView(label(detail, 11f, Theme.MUTED))
            }
            val custom = Prefs.orionEntityPhilosophy(this@CharacterSettingsActivity)
            addView(label(
                custom.ifBlank { "No owner-defined philosophy yet. The core safety and evidence principles remain active." },
                12f, Theme.SILVER
            ).apply { setPadding(0, dp(10), 0, dp(8)) })
            addView(button("Edit Orion's philosophy") { editIdentityField(
                "Entity philosophy", custom, 2400, true, Prefs::setOrionEntityPhilosophy
            ) })
            addView(label(
                "Your additions guide reasoning and values. They cannot override Android permissions, approval requirements, privacy boundaries or safety rules.",
                11f, Theme.MUTED
            ).apply { setPadding(0, dp(8), 0, 0) })
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

    private fun editIdentityField(
        title: String,
        current: String,
        maxLength: Int,
        multiline: Boolean,
        save: (android.content.Context, String) -> Unit
    ) {
        val editor = EditText(this).apply {
            setText(current)
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            textSize = 15f
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = GradientDrawable().apply {
                setColor(Theme.PANEL2)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Theme.LINE_STRONG)
            }
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = !multiline
            minLines = if (multiline) 4 else 1
            maxLines = if (multiline) 8 else 1
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLength))
            hint = if (multiline) "Write in your own words…" else "Enter a name"
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editor)
            .setPositiveButton("Save") { _, _ ->
                save(this, editor.text.toString())
                Toast.makeText(this, "$title saved", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
