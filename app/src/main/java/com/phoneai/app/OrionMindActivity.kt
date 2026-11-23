package com.phoneai.app

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** One page to see what Orion runs on and to edit the parts that shape his behaviour. */
class OrionMindActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun panel() = GradientDrawable().apply {
        setColor(Theme.PANEL); cornerRadius = dp(Theme.CORNER_MEDIUM_DP).toFloat(); setStroke(dp(1), Theme.LINE)
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Theme.TEXT); backgroundTintList = null; minHeight = dp(46)
        background = GradientDrawable().apply {
            setColor(Theme.PANEL2); cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat(); setStroke(dp(1), Theme.LINE_STRONG)
        }
        setOnClickListener { action() }
    }

    private fun editor(initial: String, lines: Int, hintText: String = "") = EditText(this).apply {
        setText(initial); hint = hintText; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED); textSize = 13f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = lines; gravity = android.view.Gravity.TOP
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            setColor(Theme.BG); cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat(); setStroke(dp(1), Theme.LINE)
        }
    }

    private fun card(title: String, note: String, body: LinearLayout.() -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = panel(); setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(label(title, 15f, Theme.TEXT, true))
            if (note.isNotBlank()) addView(label(note, 12f, Theme.MUTED))
            body()
        }
        root.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun gap(v: LinearLayout, view: android.view.View) =
        v.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(20), dp(17), dp(28)); setBackgroundColor(Theme.BG)
        }
        root.addView(label("ORION  /  MIND", 10f, Theme.SILVER, true))
        root.addView(label("Orion's mind", 27f, Theme.TEXT, true).apply { setPadding(0, dp(7), 0, dp(5)) })
        root.addView(label("What he runs on, and what you can change. Edits apply from his next reply.", 13f, Theme.MUTED))

        card("YOUR OWN RULES", "Standing instructions in your own words. They sit above his character in every reply.") {
            val box = editor(OrionMindStore.ownerRules(this@OrionMindActivity), 4, "e.g. Always give me the result first. Never open the browser unless I say so.")
            gap(this, box)
            gap(this, button("Save my rules") {
                OrionMindStore.setOwnerRules(this@OrionMindActivity, box.text.toString()); toast("Saved")
            })
        }

        card("LEARNED FROM YOUR CORRECTIONS", "When you correct him (\"no, shorter\", \"don't open the browser\") he keeps it here, in your words. Edit or clear any line.") {
            val box = editor(OrionCorrections.learned(this@OrionMindActivity).joinToString("\n"), 5, "Nothing learned yet.")
            gap(this, box)
            gap(this, button("Save") { OrionCorrections.setLearned(this@OrionMindActivity, box.text.toString()); toast("Saved") })
        }

        card("CHARACTER", "Who he is and how he sounds: philosophy, manner and worked examples. Reset restores the built-in version.") {
            val box = editor(OrionMindStore.character(this@OrionMindActivity), 12)
            gap(this, box)
            val row = LinearLayout(this@OrionMindActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("Save character") {
                OrionMindStore.setCharacter(this@OrionMindActivity, box.text.toString()); toast("Saved")
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            row.addView(button("Reset to default") {
                OrionMindStore.setCharacter(this@OrionMindActivity, null)
                box.setText(OrionPersona.CHARACTER); toast("Restored")
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            gap(this, row)
        }

        card("MOOD SCENES", "When you say something that matches, he brings the right tools and makes the first move. Switch a scene off or rewrite what it does.") {
            OrionScenes.all().forEach { scene ->
                addView(label(scene.id.uppercase(), 11f, Theme.SILVER, true).apply { setPadding(0, dp(12), 0, 0) })
                val on = Switch(this@OrionMindActivity).apply {
                    text = "Enabled"
                    textSize = 13f
                    setTextColor(Theme.TEXT)
                    isChecked = OrionMindStore.sceneEnabled(this@OrionMindActivity, scene.id)
                    setOnCheckedChangeListener { _, value -> OrionMindStore.setSceneEnabled(this@OrionMindActivity, scene.id, value) }
                }
                addView(on)
                val box = editor(OrionMindStore.sceneBrief(this@OrionMindActivity, scene.id) ?: scene.brief, 3)
                gap(this, box)
                val row = LinearLayout(this@OrionMindActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(button("Save") {
                    OrionMindStore.setSceneBrief(this@OrionMindActivity, scene.id, box.text.toString()); toast("Saved")
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
                row.addView(button("Reset") {
                    OrionMindStore.setSceneBrief(this@OrionMindActivity, scene.id, null)
                    box.setText(scene.brief); toast("Restored")
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
                gap(this, row)
            }
        }

        card("SPEED", "One model for everything is the fastest, like Ultron. Turn it on and Orion stops switching to backup models.") {
            val single = Switch(this@OrionMindActivity).apply {
                text = "Single fast model (no switching)"
                textSize = 13f
                setTextColor(Theme.TEXT)
                isChecked = !Prefs.autoSwitch(this@OrionMindActivity)
                setOnCheckedChangeListener { _, v -> Prefs.setAutoSwitch(this@OrionMindActivity, !v) }
            }
            addView(single)
        }

        card("RUN LOG", "What Orion actually did, with problems found (loops, wandering, slow steps) and what to forge next. Share it so a failure can be fixed from evidence.") {
            gap(this, button("Export run log") {
                thread(name = "orion-run-export") {
                    val text = runCatching { OrionDiagnostics.report() }.getOrElse { "Could not build the log: ${it.message}" }
                    ui.post {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                        }, "Share run log"))
                    }
                }
            })
        }

        card("WHAT HE SEES EVERY TURN", "Read-only. The exact pieces behind each reply, so nothing is hidden.") {
            val out = label("", 11f, Theme.MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
            gap(this, button("Show the live prompt") {
                out.text = "Loading..."
                thread(name = "orion-mind-view") {
                    val text = runCatching { liveView() }.getOrElse { "Could not build the view: ${it.message}" }
                    ui.post { out.text = text }
                }
            })
            gap(this, out)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Theme.BG); addView(root) })
    }

    private fun liveView(): String = buildString {
        val surface = ToolRegistry.focusedToolsFor("hello").map { it.name }
        append("TOOLS SENT EVERY TURN (").append(surface.size).append(" of ").append(ToolRegistry.all.size).append(" registered)\n")
        append(surface.joinToString(", ")).append("\n\n")
        append("IDENTITY (built in, ").append(OrionPersona.CORE.length).append(" chars)\n").append(OrionPersona.CORE).append("\n\n")
        append("CHARACTER (yours if edited)\n").append(OrionMindStore.character(this@OrionMindActivity)).append("\n\n")
        OrionMindStore.rulesBlock(this@OrionMindActivity).takeIf { it.isNotBlank() }?.let { append(it).append("\n") }
        append("OPERATING FACTS\n").append(OrionPersona.OPERATING).append("\n\n")
        append("LIVE CONTEXT FOR A PLAIN HELLO (what the central brain adds)\n")
        append(OrionCentralBrain.contextFor(this@OrionMindActivity, "hello"))
    }
}
