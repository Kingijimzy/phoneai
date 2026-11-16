package com.phoneai.app

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Code Studio page: the same state Orion drives from chat (runtime, objective, Git, build, registry) in one place. */
class StudioActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var project: EditText
    private lateinit var registry: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val live = object : Runnable {
        override fun run() { if (autoStatus) refreshStatus(); ui.postDelayed(this, 4000) }
    }
    private var autoStatus = true
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(v: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = v; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(4))
    }
    private fun chip(caption: String, action: () -> Unit) = TextView(this).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.TEXT); gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply { setColor(Theme.PANEL2); cornerRadius = dp(11).toFloat(); setStroke(dp(1), Theme.LINE_STRONG) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
        isClickable = true; setOnClickListener { action() }
    }
    private fun row(vararg chips: TextView) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; chips.forEach { addView(it) } })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG) }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(40), dp(18), dp(30)) }
        scroll.addView(page); setContentView(scroll)
        page.addView(label("ORION / CODE STUDIO", 10f, Theme.MUTED, true))
        page.addView(label("Build anything", 26f, Theme.TEXT, true))
        page.addView(label("Everything here is also available to Orion from chat. Ask it, or tap.", 12f, Theme.MUTED))
        project = EditText(this).apply {
            hint = "project folder under /workspace (blank = whole workspace)"; setHintTextColor(Theme.MUTED); setTextColor(Theme.TEXT)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; textSize = 14f; setSingleLine()
        }
        page.addView(project)
        page.addView(row(chip("Status") { autoStatus = true; refreshStatus() }, chip("Toolchains") { go("toolchain_list") }, chip("Diagnostics") { go("diagnostics") }, chip("Format") { go("format") }))
        page.addView(row(chip("Git status") { go("git", "op" to "status") }, chip("Diff") { go("git", "op" to "diff") }, chip("Log") { go("git", "op" to "log") }, chip("Commit") { go("git", "op" to "commit", "message" to "Studio commit") }))
        page.addView(row(chip("Build") { go("build") }, chip("Test") { go("test") }, chip("Preview") { go("preview") }, chip("Ubuntu") { go("toolchain_install", "name" to "python") }))
        output = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11.5f; setTextColor(Theme.TEXT); setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12)); minHeight = dp(180)
            background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Theme.LINE) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        page.addView(output)
        page.addView(label("Registry — plugins, apps, extensions, MCP", 15f, Theme.TEXT, true).apply { setPadding(0, dp(18), 0, dp(4)) })
        registry = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(registry)
        renderRegistry()
    }

    override fun onResume() { super.onResume(); ui.post(live) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(live) }

    private fun show(text: String) = runOnUiThread { output.text = text.take(12000) }

    private fun refreshStatus() {
        Thread { val t = runCatching { OrionStudio.overview(applicationContext) }.getOrElse { "status unavailable: ${it.message}" }; show(t) }.start()
    }

    private fun go(action: String, vararg extra: Pair<String, String>) {
        autoStatus = false
        show("Running $action…")
        val args = JSONObject().put("action", action).put("project", project.text.toString().trim())
        extra.forEach { args.put(it.first, it.second) }
        Thread { show(runCatching { OrionStudio.run(applicationContext, args) }.getOrElse { "error: ${it.message}" }) }.start()
    }

    private fun renderRegistry() {
        registry.removeAllViews()
        val rows = OrionStudio.registryList(applicationContext).lines()
        OrionStudio.catalog.forEach { e ->
            val installed = rows.any { it.startsWith("✓ ${e.id} ") }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Theme.LINE) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            card.addView(label("${e.name}  ·  ${e.kind.name.lowercase()}", 14f, Theme.TEXT, true))
            card.addView(label(e.about, 12f, Theme.MUTED))
            val actions = mutableListOf<TextView>()
            if (!installed) actions += chip("Install") { go("registry_install", "id" to e.id); ui.postDelayed({ renderRegistry() }, 15000) }
            else {
                if (e.run.isNotBlank()) actions += chip("Run") { go("registry_run", "id" to e.id) }
                actions += chip("Remove") { go("registry_remove", "id" to e.id); ui.postDelayed({ renderRegistry() }, 800) }
            }
            card.addView(row(*actions.toTypedArray()))
            registry.addView(card)
        }
    }
}
