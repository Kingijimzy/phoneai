package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Code Studio host: ten focused pages (editor, terminal, runtime, packages, extensions, debug, build, git, agent,
 * recovery) behind a tab bar, sharing one project and one runtime. Orion drives the same backend from chat.
 */
class StudioActivity : Activity() {
    internal lateinit var ui: StudioUi
    private val main = Handler(Looper.getMainLooper())
    private val pages = LinkedHashMap<String, StudioPage>()
    private val scrolls = HashMap<String, ScrollView>()
    private val tabs = HashMap<String, TextView>()
    private lateinit var content: FrameLayout
    private lateinit var projectField: EditText
    private lateinit var runtimePill: TextView
    private var current = "editor"
    private var detail: View? = null

    internal val project: String get() = projectField.text.toString().trim().trim('/')

    internal fun bg(block: () -> Unit) { Thread { runCatching(block) }.start() }
    internal fun post(block: () -> Unit) { runOnUiThread { runCatching(block) } }
    internal fun postDelayed(r: Runnable, ms: Long) { main.postDelayed(r, ms) }
    internal fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    internal fun currentPageId() = current
    internal fun setProject(p: String) { projectField.setText(p) }
    internal fun currentFile(): String? = (pages["editor"] as? StudioEditorPage)?.currentFile()
    internal fun openChat() { startActivity(Intent(this, MainChatActivity::class.java)) }
    internal fun scrollToBottom() { scrolls[current]?.post { scrolls[current]?.fullScroll(View.FOCUS_DOWN) } }
    internal fun runAction(action: String, show: (String) -> Unit) {
        bg {
            val r = OrionStudio.run(this, JSONObject().put("action", action).put("project", project))
            post { show(r) }
        }
    }

    /** A detail view (for example one extension's own page) pushed over the current page. */
    internal fun push(view: View) {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(24)) }
        col.addView(ui.chip("‹ Back") { pop() })
        col.addView(view)
        scroll.addView(col)
        detail = scroll
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.setBackgroundColor(Theme.BG)
    }

    private fun pop() { detail?.let { content.removeView(it) }; detail = null }

    override fun onBackPressed() { if (detail != null) pop() else super.onBackPressed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = StudioUi(this)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val prefs = getSharedPreferences("studio_state", 0)

        val rootView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG) }

        // top bar: title, project, runtime
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(38), ui.dp(18), ui.dp(6)) }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(ui.label("CODE STUDIO", 12f, Theme.TEXT, true).apply { letterSpacing = .12f }, LinearLayout.LayoutParams(0, -2, 1f))
        runtimePill = ui.badge("", Theme.OK)
        titleRow.addView(runtimePill)
        top.addView(titleRow)
        projectField = ui.field("project folder under /workspace (blank = whole workspace)").apply { setText(prefs.getString("project", "")) }
        top.addView(projectField)
        rootView.addView(top, LinearLayout.LayoutParams(-1, -2))

        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // tab bar
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(10)); setBackgroundColor(Theme.PANEL) }
        val all: List<StudioPage> = listOf(
            StudioEditorPage(this), StudioTerminalPage(this), StudioRuntimePage(this), StudioPackagesPage(this), StudioExtensionsPage(this),
            StudioDebugPage(this), StudioBuildPage(this), StudioGitPage(this), StudioAgentPage(this), StudioRecoveryPage(this)
        )
        all.forEach { p ->
            pages[p.id] = p
            val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG); visibility = View.GONE }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(30)) }
            scroll.addView(col)
            p.build(col)
            scrolls[p.id] = scroll
            content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            val tab = ui.chip(p.title) { show(p.id) }
            tabs[p.id] = tab; tabRow.addView(tab)
        }
        rootView.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setBackgroundColor(Theme.PANEL); addView(tabRow) }, LinearLayout.LayoutParams(-1, -2))
        setContentView(rootView)

        projectField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { prefs.edit().putString("project", s.toString().trim()).apply() }
        })
        handle(intent)
        if (intent.getStringExtra("page") == null && intent.getStringExtra("file") == null && intent.getStringExtra("snippet") == null) show(prefs.getString("page", "editor").orEmpty())
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun handle(i: Intent) {
        i.getStringExtra("file")?.let { show("editor"); (pages["editor"] as? StudioEditorPage)?.openFile(it) }
        i.getStringExtra("snippet")?.let { code -> show("editor"); (pages["editor"] as? StudioEditorPage)?.openSnippet(code, i.getStringExtra("lang").orEmpty()) }
        i.getStringExtra("page")?.let { show(it) }
    }

    private fun show(id: String) {
        val key = if (pages.containsKey(id)) id else "editor"
        pop()
        current = key
        scrolls.forEach { (k, v) -> v.visibility = if (k == key) View.VISIBLE else View.GONE }
        tabs.forEach { (k, t) ->
            val sel = k == key
            t.setTextColor(if (sel) Theme.BG else Theme.TEXT)
            (t.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (sel) Theme.SILVER else Theme.PANEL2)
        }
        getSharedPreferences("studio_state", 0).edit().putString("page", key).apply()
        runtimePill.text = OrionUbuntu.label(this).uppercase()
        pages[key]?.onShow()
    }

    override fun onResume() { super.onResume(); runtimePill.text = OrionUbuntu.label(this).uppercase() }
}
