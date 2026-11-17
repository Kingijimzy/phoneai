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
 * Code Studio host: ten focused pages grouped into five tabs (Code, Terminal, Agent, Project, Tools), sharing one project and one runtime. Orion drives the same backend from chat.
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
    private var progressListener: ((OrionProgress.State) -> Unit)? = null
    private val groups = listOf(
        Triple("editor", "Code", listOf("editor")),
        Triple("terminal", "Terminal", listOf("terminal")),
        Triple("agent", "Agent", listOf("agent")),
        Triple("project", "Project", listOf("git", "build", "debug", "recovery")),
        Triple("tools", "Tools", listOf("runtime", "packages", "extensions"))
    )
    private fun groupOf(id: String): String = groups.firstOrNull { it.first == id || id in it.third }?.first ?: "editor"
    private var detail: View? = null

    internal val project: String get() = OrionProjectContext.get(this)

    internal fun bg(block: () -> Unit) { Thread { runCatching(block) }.start() }
    internal fun post(block: () -> Unit) { runOnUiThread { runCatching(block) } }
    internal fun postDelayed(r: Runnable, ms: Long) { main.postDelayed(r, ms) }
    internal fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    internal fun currentPageId() = current
    internal fun setProject(p: String) { projectField.setText(OrionProjectContext.set(this, p)) }
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
        val initialProject = OrionProjectContext.get(this)
        projectField = ui.field("project folder under /workspace (blank = whole workspace)").apply { setText(initialProject) }
        OrionProjectContext.set(this, initialProject)
        top.addView(projectField)
        rootView.addView(top, LinearLayout.LayoutParams(-1, -2))

        content = FrameLayout(this)
        rootView.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // one live progress strip for every download/install, with sizes
        val strip = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(6), ui.dp(18), ui.dp(6)); setBackgroundColor(Theme.PANEL); visibility = View.GONE }
        val stripText = ui.label("", 11f, Theme.TEXT)
        val stripBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; isIndeterminate = true }
        strip.addView(stripText); strip.addView(stripBar, LinearLayout.LayoutParams(-1, ui.dp(6)))
        rootView.addView(strip, LinearLayout.LayoutParams(-1, -2))
        progressListener = { st ->
            runOnUiThread {
                if (st.label.isBlank() && !st.active) { strip.visibility = View.GONE } else {
                    strip.visibility = View.VISIBLE
                    stripText.text = (if (st.active) "⬇ " else if (st.ok == true) "✓ " else "✕ ") + st.label +
                        (if (st.detail.isNotBlank()) " · " + st.detail else "") +
                        (if (st.size.isNotBlank()) " · " + st.size else "") + (if (st.percent >= 0) " · ${st.percent}%" else "")
                    stripBar.isIndeterminate = st.active && st.percent < 0
                    if (st.percent >= 0) stripBar.progress = st.percent
                    if (!st.active) main.postDelayed({ if (!OrionProgress.state.active) strip.visibility = View.GONE }, 6000)
                }
            }
        }
        OrionProgress.listen(progressListener!!)

        // five tabs only; related pages share one scrolling page (the harness pattern: few tabs, everything near)
        val all = listOf(
            StudioEditorPage(this), StudioTerminalPage(this), StudioAgentPage(this), StudioGitPage(this), StudioBuildPage(this),
            StudioDebugPage(this), StudioRecoveryPage(this), StudioRuntimePage(this), StudioPackagesPage(this), StudioExtensionsPage(this)
        ).associateBy { it.id }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(10), ui.dp(6), ui.dp(2), ui.dp(10)); setBackgroundColor(Theme.PANEL) }
        groups.forEach { (gid, gtitle, members) ->
            val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG); visibility = View.GONE }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(4), ui.dp(18), ui.dp(30)) }
            scroll.addView(col)
            members.forEach { mid ->
                val p = all.getValue(mid)
                pages[mid] = p
                if (members.size > 1) col.addView(ui.section(p.title))
                p.build(col)
            }
            scrolls[gid] = scroll
            content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            val tab = ui.chip(gtitle) { show(gid) }
            tab.textSize = 12f
            tab.setPadding(ui.dp(4), ui.dp(9), ui.dp(4), ui.dp(9))
            tab.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8) }
            tabs[gid] = tab; tabRow.addView(tab)
        }
        rootView.addView(tabRow, LinearLayout.LayoutParams(-1, -2))
        setContentView(rootView)

        projectField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s?.toString().orEmpty().trim()
                runCatching { OrionProjectContext.set(this@StudioActivity, value) }
                    .onSuccess { pages["terminal"]?.onShow() }
                    .onFailure { toast("Invalid project path: use a folder under /workspace") }
            }
        })
        handle(intent)
        if (intent.getStringExtra("page") == null && intent.getStringExtra("file") == null && intent.getStringExtra("snippet") == null) show(prefs.getString("page", "editor").orEmpty())
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun handle(i: Intent) {
        val activeProject = OrionProjectContext.get(this)
        if (projectField.text.toString().trim().trim('/') != activeProject) projectField.setText(activeProject)
        i.getStringExtra("file")?.let { show("editor"); (pages["editor"] as? StudioEditorPage)?.openFile(it) }
        i.getStringExtra("snippet")?.let { code -> show("editor"); (pages["editor"] as? StudioEditorPage)?.openSnippet(code, i.getStringExtra("lang").orEmpty()) }
        i.getStringExtra("page")?.let { show(it) }
    }

    private fun show(id: String) {
        val key = groupOf(id)
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
        // Paint the tab first, then refresh its pages one frame apart so a tap never waits on git/disk work.
        val shown = key
        groups.first { it.first == key }.third.forEachIndexed { i, pid ->
            scrolls[key]?.postDelayed({ if (current == shown) pages[pid]?.onShow() }, 40L * (i + 1))
        }
        if (id != key) scrollToSection(id)
    }

    private fun scrollToSection(id: String) {
        val sc = scrolls[current] ?: return
        val title = pages[id]?.title?.uppercase() ?: return
        sc.post {
            val col = sc.getChildAt(0) as? LinearLayout ?: return@post
            for (i in 0 until col.childCount) {
                val v = col.getChildAt(i)
                if (v is TextView && v.text.toString() == title) { sc.smoothScrollTo(0, v.top); break }
            }
        }
    }

    override fun onDestroy() { progressListener?.let { OrionProgress.unlisten(it) }; super.onDestroy() }

    override fun onResume() {
        super.onResume()
        runtimePill.text = OrionUbuntu.label(this).uppercase()
        val active = OrionProjectContext.get(this)
        if (projectField.text.toString().trim().trim('/') != active) projectField.setText(active)
    }
}
