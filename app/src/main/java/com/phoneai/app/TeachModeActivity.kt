package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
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

class TeachModeActivity : Activity(), PhoneAiService.MainChatListener {
    private lateinit var nameInput: EditText
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var stopReplayButton: TextView
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    /** Subtle top-lit glass surface shared by Teach Mode controls. */
    private fun shape(fill: Int, stroke: Int = Theme.LINE) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(fill, 0xE8131313.toInt())
    ).apply {
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), if (stroke == Theme.LINE) 0x555F5F5F else stroke)
    }
    private fun button(s: String, action: () -> Unit) = label(s, 13f, Theme.CYAN, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(13), dp(12), dp(13), dp(12))
        background = shape(Theme.PANEL2, Theme.LINE)
        isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(16)); setBackgroundColor(Theme.BG)
        }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(OrionIconView(this).apply {
            icon = "arrow"; tint = Theme.TEXT; contentDescription = "Back"
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(46)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Teach Mode", 22f, Theme.TEXT, true))
        titles.addView(label("Record once. Replay locally.", 12f, Theme.MUTED))
        head.addView(titles); root.addView(head)
        root.addView(label("Record your own taps, long presses, swipes and waits once. Routines are saved on this phone and replay without AI calls or token use.", 13f, Theme.MUTED).apply {
            setPadding(dp(2), dp(14), dp(2), dp(16))
        })
        nameInput = EditText(this).apply {
            hint = "Routine name, e.g. FC daily rewards"; setHintTextColor(0xFF8D8D8D.toInt())
            setTextColor(Theme.TEXT); textSize = 14f; setSingleLine(true)
            background = shape(Theme.PANEL); setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        root.addView(nameInput, LinearLayout.LayoutParams(-1, dp(50)))
        root.addView(button("START RECORDING") {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) { nameInput.error = "Name this routine first"; return@button }
            val service = PhoneAiService.instance
            if (service == null) {
                status.text = "Enable Orion in Android Accessibility settings first."
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                val result = service.startTeach(name)
                status.text = if (result == "ok") "Recording $name. Do the sequence, then tap the red REC bar at the top to save." else result
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        status = label("Test routines carefully before replaying actions that submit forms or spend money.", 12f, Theme.MUTED)
        status.setPadding(dp(2), dp(12), dp(2), dp(8)); root.addView(status)
        stopReplayButton = button("STOP CURRENT TASK") {
            val service = PhoneAiService.instance
            if (service?.isTaskRunning() == true) {
                service.stopCurrentTask()
                status.text = "Stop requested. Orion will stop at the next safe point."
            } else {
                status.text = "No routine is currently running."
            }
        }.apply { isEnabled = false; alpha = 0.42f }
        root.addView(stopReplayButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        root.addView(label("YOUR ROUTINES", 10f, Theme.CYAN, true).apply { letterSpacing = .12f; setPadding(0, dp(8), 0, dp(8)) })
        val scroll = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root); refresh()
    }
    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) refresh()
        PhoneAiService.instance?.attachMainChat(this)
    }

    override fun onPause() {
        PhoneAiService.instance?.detachMainChat(this)
        super.onPause()
    }

    override fun onMessage(kind: String, message: String) {
        runOnUiThread {
            if (::status.isInitialized && (kind == "note" || kind == "ai")) status.text = message
        }
    }

    override fun onStatus(value: String, mode: OrbView.Mode) {
        runOnUiThread {
            if (::status.isInitialized && (mode == OrbView.Mode.THINKING || mode == OrbView.Mode.ACTING)) status.text = value
            if (::stopReplayButton.isInitialized) {
                // Keep Stop available while Orion is waiting for a real approval too.
                // A user should never have to approve or wait through an action to cancel it.
                val active = mode == OrbView.Mode.THINKING ||
                    mode == OrbView.Mode.ACTING ||
                    mode == OrbView.Mode.WAITING_APPROVAL
                stopReplayButton.isEnabled = active
                stopReplayButton.alpha = if (active) 1f else 0.42f
                stopReplayButton.text = if (mode == OrbView.Mode.WAITING_APPROVAL)
                    "CANCEL WAITING TASK" else "STOP CURRENT TASK"
            }
        }
    }
    private fun refresh() {
        list.removeAllViews()
        val names = MacroStore.names(this)
        if (names.isEmpty()) {
            list.addView(label("No routines yet. Start a recording above.", 13f, Theme.MUTED).apply { setPadding(dp(8), dp(12), dp(8), dp(12)) })
            return
        }
        names.forEachIndexed { index, name ->
            val count = MacroStore.find(this, name)?.second?.length() ?: 0
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12)); background = shape(Theme.PANEL)
            }
            card.addView(label(name, 15f, Theme.TEXT, true))
            card.addView(label("$count recorded steps · offline replay", 11f, Theme.MUTED).apply { setPadding(0, dp(4), 0, dp(10)) })
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            actions.addView(button("▶ Replay") {
                val service = PhoneAiService.instance
                if (service == null) {
                    Toast.makeText(this@TeachModeActivity, "Enable Orion Accessibility first.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else if (service.isTaskRunning()) {
                    status.text = "Orion is already running another task. Stop it or wait for it to finish first."
                } else {
                    val savedSteps = MacroStore.find(this@TeachModeActivity, name)?.second
                    val previewLines = mutableListOf<String>()
                    if (savedSteps != null) {
                        for (i in 0 until minOf(savedSteps.length(), 8)) {
                            val step = savedSteps.optJSONObject(i) ?: continue
                            val tool = step.optString("tool", "action")
                            val args = step.optJSONObject("args") ?: org.json.JSONObject()
                            val detail = when (tool) {
                                "click_text" -> args.optString("text")
                                "open_app" -> args.optString("name")
                                "open_url", "browser_navigate" -> args.optString("url")
                                "type" -> "text entry (content hidden)"
                                "tap", "long_press" -> "screen coordinates"
                                "swipe", "swipe_path", "scroll" -> "screen gesture"
                                "wait" -> "short wait"
                                "key" -> args.optString("key")
                                else -> ""
                            }
                            previewLines.add("${i + 1}. $tool" + if (detail.isBlank()) "" else " — $detail")
                        }
                        if (savedSteps.length() > 8) previewLines.add("…and ${savedSteps.length() - 8} more steps")
                    }
                    val previewText = if (previewLines.isEmpty()) "No step preview is available. " else "Steps preview:\n" + previewLines.joinToString("\n") + "\n\n"
                    AlertDialog.Builder(this@TeachModeActivity)
                        .setTitle("Replay $name?")
                        .setMessage(previewText + "This routine contains $count recorded steps and replays locally without AI calls. Keep the target app open and make sure the screen is in the expected starting state. Recorded taps may submit forms or change data.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Replay routine") { _, _ ->
                            val currentService = PhoneAiService.instance
                            if (currentService == null) {
                                status.text = "Orion Accessibility is no longer connected."
                            } else if (currentService.isTaskRunning()) {
                                status.text = "Another task started. Stop it or wait before replaying."
                            } else {
                                status.text = "Replaying $name locally — no AI calls."
                                currentService.sendFromMainChat("run $name")
                            }
                        }.show()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(button("Delete") {
                AlertDialog.Builder(this@TeachModeActivity)
                    .setTitle("Delete routine?")
                    .setMessage("Delete '$name' and its $count recorded steps from this phone? This cannot be undone.")
                    .setNegativeButton("Keep routine", null)
                    .setPositiveButton("Delete") { _, _ ->
                        if (MacroStore.delete(this@TeachModeActivity, name)) {
                            status.text = "Deleted $name"
                            refresh()
                        } else status.text = "Could not delete this routine."
                    }.show()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            card.addView(actions)
            card.alpha = 0f
            card.translationY = dp(8).toFloat()
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
            // A restrained stagger makes routine cards feel responsive without a heavy renderer.
            card.animate().alpha(1f).translationY(0f)
                .setDuration(220L)
                .setStartDelay((index * 35L).coerceAtMost(175L))
                .start()
        }
    }
}