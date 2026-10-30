package com.phoneai.app

import androidx.activity.ComponentActivity

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * Orion app entry point. The home and primary navigation now render in Jetpack Compose;
 * existing activities continue to own the agent, browser, settings, and device integrations.
 */
class MainActivity : ComponentActivity() {
    companion object {
        @Volatile private var crashDiagnosticsInstalled = false
    }

    private lateinit var content: FrameLayout
    private lateinit var statusLabel: TextView
    private var selectedTab = "Home"
    private var composeStatus: MutableState<String>? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun surface(radius: Int = 22, fill: Int = Theme.PANEL): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Theme.LINE)
        }

    private fun gradientSurface(radius: Int, colors: IntArray): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), 0xFF5A5A5A.toInt())
        }

    private fun animateEntrance(view: View, delay: Long = 0L) {
        view.alpha = 0f
        view.translationY = dp(12).toFloat()
        view.scaleX = .985f
        view.scaleY = .985f
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(delay).setDuration(360)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun action(title: String, subtitle: String? = null, primary: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = surface(17, if (primary) 0xFF1A1A1A.toInt() else 0xFF111111.toInt())
            isClickable = true
            isFocusable = true
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val glyph = OrionIconView(this).apply {
            icon = iconFor(title)
            tint = if (primary) Theme.CYAN else Theme.MUTED
            contentDescription = null
        }
        heading.addView(glyph, LinearLayout.LayoutParams(dp(23), dp(23)).apply { rightMargin = dp(10) })
        heading.addView(label(title, 15f, if (primary) Theme.CYAN else Theme.TEXT, true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(OrionIconView(this).apply {
            icon = "arrow"
            tint = Theme.MUTED
            alpha = 0.7f
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(18), dp(18)))
        row.addView(heading)
        if (subtitle != null) {
            row.addView(label(subtitle, 12f, Theme.MUTED).apply {
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(dp(1).toFloat(), 1f)
                setPadding(dp(33), dp(4), dp(2), 0)
            })
        }
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(.985f).scaleY(.985f).setDuration(80).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun iconFor(title: String): String {
        val t = title.lowercase()
        return when {
            "chat" in t || "conversation" in t -> "chat"
            "task" in t || "routine" in t -> "tasks"
            "memory" in t -> "memory"
            "skill" in t -> "skills"
            "browser" in t || "research" in t -> "browser"
            "network" in t || "game" in t || "diagnostic" in t -> "network"
            "screen" in t || "permission" in t || "privacy" in t -> "shield"
            "voice" in t || "character" in t -> "voice"
            "provider" in t || "service" in t || "integration" in t -> "spark"
            "accessibility" in t -> "settings"
            else -> "spark"
        }
    }

    private fun section(parent: LinearLayout, heading: String, note: String? = null): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(17), 0, dp(2))
        }
        wrap.addView(label(heading, 17f, Theme.TEXT, true))
        if (note != null) wrap.addView(label(note, 12f, Theme.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        parent.addView(wrap)
        return wrap
    }

    private fun gap(parent: LinearLayout, view: View, top: Int = 9) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashRecorder()
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        window.decorView.systemUiVisibility = 0
        val statusState = mutableStateOf(buildStatusText())
        composeStatus = statusState
        val composeRoot = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                OrionComposeShell(
                    status = statusState.value,
                    onOpenChat = { launchDestination(Intent(this@MainActivity, MainChatActivity::class.java), "MainChatActivity") },
                    onOpenVoice = { launchDestination(Intent(this@MainActivity, LiveConversationActivity::class.java), "LiveConversationActivity") },
                    onOpenBrowser = { launchDestination(Intent(this@MainActivity, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, "https://duckduckgo.com/"), "BrowserActivity") },
                    onOpenTasks = { launchDestination(Intent(this@MainActivity, TaskCenterActivity::class.java), "TaskCenterActivity") },
                    onOpenSettings = { section -> openSettingsSection(section) },
                    onOpenUtilities = { launchDestination(Intent(this@MainActivity, TeachModeActivity::class.java), "TeachModeActivity") },
                    onOpenTrading = { launchDestination(Intent(this@MainActivity, TradingActivity::class.java), "TradingActivity") },
                    onOpenMemory = { launchDestination(Intent(this@MainActivity, MemoryActivity::class.java), "MemoryActivity") },
                    onOpenAgents = { launchDestination(Intent(this@MainActivity, AgentsActivity::class.java), "AgentsActivity") },
                    onOpenSoulEngine = { launchDestination(Intent(this@MainActivity, SoulEngineActivity::class.java), "SoulEngineActivity") },
                    onOpenExtensions = { launchDestination(Intent(this@MainActivity, SelfExtensionLabActivity::class.java), "SelfExtensionLabActivity") },
                    onOpenFeature = { route -> openFeature(route) }
                )
            }
        }
        setContentView(composeRoot)
        showPreviousCrashIfPresent()
        AccessibilityDisclosure.showFirstRun(this)
    }
    private fun launchDestination(intent: Intent, destination: String) {
        val diagnostics = getSharedPreferences("orion_navigation_diagnostics", MODE_PRIVATE)
        diagnostics.edit()
            .putString("pending_destination", destination)
            .putLong("launch_requested_at", System.currentTimeMillis())
            .apply()
        try {
            startActivity(intent)
        } catch (error: Exception) {
            diagnostics.edit().remove("pending_destination").apply()
            AgentPolicy.log(this, "navigation:$destination", "open", false, error.javaClass.simpleName + ": " + (error.message ?: "launch failed"))
            Toast.makeText(this, "Orion could not open $destination. The error was recorded for diagnostics.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Send a settings shortcut to the feature that owns it. The legacy control
     * room remains the fallback only for controls without a dedicated screen.
     */
    private fun openSettingsSection(section: String?) {
        val destination: Class<out android.app.Activity>
        val intent: Intent
        when (section) {
            "memory" -> { destination = MemoryActivity::class.java; intent = Intent(this, destination) }
            "routines" -> { destination = TaskCenterActivity::class.java; intent = Intent(this, destination) }
            "skills" -> { destination = AgentControlActivity::class.java; intent = Intent(this, destination) }
            "knowledge" -> { destination = KnowledgeGraphActivity::class.java; intent = Intent(this, destination) }
            "integrations" -> { destination = CapabilityBoardActivity::class.java; intent = Intent(this, destination) }
            "health" -> { destination = OrionCapabilityReportActivity::class.java; intent = Intent(this, destination) }
            "browser" -> {
                destination = BrowserActivity::class.java
                intent = Intent(this, destination).putExtra(BrowserActivity.EXTRA_URL, "https://duckduckgo.com/")
            }
            "tools" -> { destination = DeviceUtilitiesActivity::class.java; intent = Intent(this, destination) }
            else -> {
                destination = SettingsActivity::class.java
                intent = Intent(this, destination)
                if (section != null) intent.putExtra("open_section", section)
            }
        }
        launchDestination(intent, destination.simpleName + (section?.let { "/$it" } ?: ""))
    }

    private fun openFeature(route: String) {
        val destination: Class<out android.app.Activity>
        val intent: Intent
        when (route) {
            "settings.providers", "settings.voice", "settings.character", "settings.memory",
            "settings.skills", "settings.knowledge", "settings.integrations", "settings.permissions", "settings.routines",
            "settings.health", "settings.recording", "settings.browser", "settings.tools", "settings.all" -> {
                openSettingsSection(route.substringAfter("settings.").takeUnless { it == "all" })
                return
            }
            "chat" -> { destination = MainChatActivity::class.java; intent = Intent(this, destination) }
            "live" -> { destination = LiveConversationActivity::class.java; intent = Intent(this, destination) }
            "browser" -> { destination = BrowserActivity::class.java; intent = Intent(this, destination).putExtra(BrowserActivity.EXTRA_URL, "https://duckduckgo.com/") }
            "tasks" -> { destination = TaskCenterActivity::class.java; intent = Intent(this, destination) }
            "agents" -> { destination = AgentsActivity::class.java; intent = Intent(this, destination) }
            "teach" -> { destination = TeachModeActivity::class.java; intent = Intent(this, destination) }
            "memory" -> { destination = MemoryActivity::class.java; intent = Intent(this, destination) }
            "knowledge_graph" -> { destination = KnowledgeGraphActivity::class.java; intent = Intent(this, destination) }
            "proactive" -> { destination = ProactiveActivity::class.java; intent = Intent(this, destination) }
            "trading" -> { destination = TradingActivity::class.java; intent = Intent(this, destination) }
            "gaming" -> { destination = GamingCoachActivity::class.java; intent = Intent(this, destination) }
            "device_utilities" -> { destination = DeviceUtilitiesActivity::class.java; intent = Intent(this, destination) }
            "soul_engine" -> { destination = SoulEngineActivity::class.java; intent = Intent(this, destination) }
            "watcher" -> { destination = SoulWatcherActivity::class.java; intent = Intent(this, destination) }
            "extensions" -> { destination = SelfExtensionLabActivity::class.java; intent = Intent(this, destination) }
            "terminal" -> { destination = TerminalActivity::class.java; intent = Intent(this, destination) }
            "workspace" -> { destination = WorkspaceShellActivity::class.java; intent = Intent(this, destination) }
            "cloud_coding" -> { destination = CursorCloudAgentActivity::class.java; intent = Intent(this, destination) }
            "capabilities" -> { destination = CapabilityBoardActivity::class.java; intent = Intent(this, destination) }
            "capability_report" -> { destination = OrionCapabilityReportActivity::class.java; intent = Intent(this, destination) }
            "agent_control" -> { destination = AgentControlActivity::class.java; intent = Intent(this, destination) }
            "termux_bridge" -> { destination = TermuxBridgeActivity::class.java; intent = Intent(this, destination) }
            "owner_control" -> { destination = OwnerControlCenterActivity::class.java; intent = Intent(this, destination) }
            "gemini_live" -> { destination = GeminiLiveActivity::class.java; intent = Intent(this, destination) }
            else -> {
                Toast.makeText(this, "This Orion destination is not registered yet: $route", Toast.LENGTH_LONG).show()
                AgentPolicy.log(this, "navigation:$route", "resolve", false, "route is not registered")
                return
            }
        }
        launchDestination(intent, destination.simpleName)
    }

    /**
     * Diagnostic reports stay in app-private storage. No crash data is uploaded.
     * The default handler also records failures in secondary activities such as Chat and Settings.
     */
    private fun installCrashRecorder() {
        if (crashDiagnosticsInstalled) return
        crashDiagnosticsInstalled = true
        val app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityResumed(activity: android.app.Activity) {
                app.getSharedPreferences("orion_navigation_diagnostics", Context.MODE_PRIVATE).edit()
                    .putString("last_activity", activity.javaClass.simpleName)
                    .putLong("last_resumed_at", System.currentTimeMillis())
                    .remove("pending_destination")
                    .apply()
            }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = StringWriter()
                PrintWriter(writer).use { error.printStackTrace(it) }
                val nav = app.getSharedPreferences("orion_navigation_diagnostics", Context.MODE_PRIVATE)
                val report = buildString {
                    appendLine("Orion crash report")
                    appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date()))
                    appendLine("Thread: " + thread.name)
                    appendLine("Android: " + android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")")
                    appendLine("Last resumed screen: " + nav.getString("last_activity", "unknown"))
                    appendLine("Pending destination: " + nav.getString("pending_destination", "none"))
                    appendLine("Launch requested at: " + nav.getLong("launch_requested_at", 0L))
                    appendLine()
                    append(writer.toString().take(24000))
                }
                File(app.filesDir, "orion_last_crash.txt").writeText(report)
            } catch (_: Throwable) {
                // Never prevent Android's normal crash handling if report writing fails.
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun showPreviousCrashIfPresent() {
        val reportFile = File(filesDir, "orion_last_crash.txt")
        if (!reportFile.exists()) return
        val report = try { reportFile.readText().take(24000) } catch (_: Exception) { return }
        AlertDialog.Builder(this)
            .setTitle("Orion saved a crash report")
            .setMessage("Orion closed unexpectedly last time. Copy this report and send it here so I can identify the exact cause. The report stays on your phone until you choose to copy it.\\n\\n" + report.take(1800))
            .setPositiveButton("Copy report") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Orion crash report", report))
                Toast.makeText(this, "Crash report copied. Paste it into this chat.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun bottomNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(0xF5121212.toInt())
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Theme.LINE)
            }
            elevation = dp(8).toFloat()
        }
        listOf("Home", "Chat", "Tools", "Settings").forEach { tab ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(5), dp(3), dp(4))
                isClickable = true
                isFocusable = true
                background = if (selectedTab == tab) GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF2E2E2E.toInt(), 0xFF191919.toInt())).apply {
                    cornerRadius = dp(17).toFloat()
                    setStroke(dp(1), 0xFF616161.toInt())
                } else GradientDrawable().apply {
                    setColor(0x00000000)
                    cornerRadius = dp(17).toFloat()
                }
            }
            val glyph = OrionIconView(this).apply {
                icon = when (tab) { "Home" -> "home"; "Chat" -> "chat"; "Tools" -> "tools"; else -> "settings" }
                tint = if (selectedTab == tab) Theme.CYAN else Theme.MUTED
                contentDescription = tab + " tab"
            }
            cell.addView(glyph, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                topMargin = dp(1)
            })
            cell.addView(label(tab, 10f, if (selectedTab == tab) Theme.CYAN else Theme.MUTED, selectedTab == tab).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3), 0, 0)
            })
            cell.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            cell.setOnClickListener { showTab(tab) }
            bar.addView(cell)
        }
        return bar
    }

    private fun showTab(tab: String, animate: Boolean = true) {
        if (tab == "Chat") {
            // Chat is a separate full-screen activity; preserve the current home/tools/settings
            // selection so returning from chat does not leave the shell in a mismatched tab state.
            startActivity(Intent(this, MainChatActivity::class.java))
            return
        }
        selectedTab = tab
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF080808.toInt(), 0xFF0E0E0E.toInt(), 0xFF101010.toInt()))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(22), dp(19), dp(24))
        }
        scroll.addView(page)
        when (tab) {
            "Home" -> buildHome(page)
            "Tools" -> buildTools(page)
            else -> buildSettings(page)
        }
        val next = FrameLayout(this)
        next.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        next.alpha = if (animate) 0f else 1f
        next.translationY = if (animate) dp(8).toFloat() else 0f
        content.removeAllViews()
        content.addView(next, FrameLayout.LayoutParams(-1, -1))
        if (animate) next.animate().alpha(1f).translationY(0f).setDuration(190)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        // Rebuild nav selection to keep the selected state visible.
        val root = content.parent as? LinearLayout ?: return
        if (root.childCount > 1) {
            root.removeViewAt(1)
            root.addView(bottomNav(), LinearLayout.LayoutParams(-1, dp(66)).apply {
                leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(7)
            })
        }
    }

    private fun buildHome(page: LinearLayout) {
        // Editorial home: near-black canvas, neutral silver accents, clearer hierarchy and fewer noisy cards.
        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandMark = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF3D3D3D.toInt(), 0xFF191919.toInt())).apply {
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), 0xFF626262.toInt())
            }
            addView(OrionIconView(this@MainActivity).apply {
                icon = "spark"; tint = Theme.CYAN; contentDescription = null
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        brandRow.addView(brandMark, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandText.addView(label("ORION", 14f, Theme.TEXT, true).apply { letterSpacing = .12f })
        brandText.addView(label("PERSONAL INTELLIGENCE", 9f, Theme.MUTED, true).apply { letterSpacing = .08f; setPadding(0, dp(2), 0, 0) })
        brandRow.addView(brandText)
        brandRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val livePill = label("READY", 9f, Theme.OK, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(0xFF1A1A1A.toInt()); cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0xFF444444.toInt())
            }
        }
        brandRow.addView(livePill)
        page.addView(brandRow)
        page.addView(label("A little more\ncapable. Every day.", 30f, Theme.TEXT, true).apply {
            setPadding(0, dp(24), 0, dp(8))
            setLineSpacing(dp(1).toFloat(), 1f)
            letterSpacing = -.035f
        })
        page.addView(label("Your assistant for thinking clearly, getting things done, and staying in control.", 13f, Theme.MUTED).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, dp(8), 0)
        })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(16), dp(18), dp(17))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF181818.toInt(), 0xFF0E0E0E.toInt(), 0xFF131313.toInt())).apply {
                cornerRadius = dp(27).toFloat()
                setStroke(dp(1), 0xFF393939.toInt())
            }
            elevation = dp(2).toFloat()
        }
        val avatar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = "Orion monochrome status waveform. Tap to open chat."
            isClickable = true
            isFocusable = true
            val heights = intArrayOf(8, 12, 17, 11, 22, 15, 19, 10, 7)
            heights.forEachIndexed { index, height ->
                val bar = View(this@MainActivity).apply {
                    background = GradientDrawable().apply {
                        setColor(if (index == 4) Theme.TEXT else Theme.LINE)
                        cornerRadius = dp(2).toFloat()
                    }
                }
                addView(bar, LinearLayout.LayoutParams(dp(3), dp(height)).apply {
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                })
            }
            setOnClickListener { showTab("Chat") }
        }
        hero.addView(avatar, LinearLayout.LayoutParams(-1, dp(34)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(10)
        })
        hero.addView(label("ORION  /  ONLINE", 10f, Theme.TEXT, true).apply { gravity = Gravity.CENTER; letterSpacing = .13f })
        hero.addView(label("Ready when you are.", 18f, Theme.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(4)) })
        hero.addView(label("Talk naturally, explore an idea, or ask for help with a task.", 12f, Theme.MUTED).apply {
            gravity = Gravity.CENTER; setLineSpacing(dp(2).toFloat(), 1f)
        })
        gap(page, hero, 20)
        animateEntrance(hero, 70)

        val chatAction = action("Start a conversation", "A quiet space to think, ask, and create", true) { showTab("Chat") }
        gap(page, chatAction, 8)
        animateEntrance(chatAction, 130)

        val imageAction = action("Ask with an image", "Choose a photo or screenshot for Orion to analyse") {
            startActivity(Intent(this@MainActivity, MainChatActivity::class.java)
                .putExtra(MainChatActivity.EXTRA_PICK_IMAGE, true))
        }
        gap(page, imageAction, 12)
        animateEntrance(imageAction, 175)

        val quickHeader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(23), 0, dp(4)) }
        quickHeader.addView(label("QUICK ACCESS", 10f, Theme.CYAN, true).apply { letterSpacing = .13f })
        quickHeader.addView(label("Pick up where you need to be.", 17f, Theme.TEXT, true).apply { setPadding(0, dp(5), 0, 0) })
        page.addView(quickHeader)
        val quickLaunch = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val live = action("Live voice", "Speak with Orion") {
            startActivity(Intent(this@MainActivity, LiveConversationActivity::class.java))
        }
        val browser = action("Orion Browser", "Search and browse") {
            startActivity(Intent(this@MainActivity, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, "https://duckduckgo.com/"))
        }
        quickLaunch.addView(live, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        quickLaunch.addView(browser, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        gap(page, quickLaunch, 8)

        val workspace = section(page, "Your workspace", "Tools and memory, organized in one place")
        gap(workspace, action("Task centre", "Running tasks, saved sessions, and progress", true) {
            startActivity(Intent(this, TaskCenterActivity::class.java))
        }, 8)
        gap(workspace, action("Capability report", "See what is ready, limited, blocked, or still unverified", true) {
            startActivity(Intent(this, OrionCapabilityReportActivity::class.java))
        }, 8)
        gap(workspace, action("Memory & routines", "Search and manage Orion’s saved local memories") {
            startActivity(Intent(this, MemoryActivity::class.java))
        }, 8)
        gap(workspace, action("Skills studio", "Manage Orion's available capabilities") {
            openSettingsSection("skills")
        }, 8)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = surface(18, 0xFF111111.toInt())
        }
        statusCard.addView(label("SYSTEM STATUS", 10f, Theme.CYAN, true).apply { letterSpacing = .12f })
        statusLabel = label("", 12f, Theme.MUTED).apply { setPadding(0, dp(7), 0, 0); setLineSpacing(dp(2).toFloat(), 1f) }
        statusCard.addView(statusLabel)
        gap(page, statusCard, 18)
        refreshStatus()
        animateEntrance(statusCard, 200)
    }

    private fun roundedPill(): GradientDrawable = GradientDrawable().apply {
        setColor(0xFF2F2F2F.toInt())
        cornerRadius = dp(30).toFloat()
        setStroke(dp(1), 0xFF515151.toInt())
    }

    private fun buildTools(page: LinearLayout) {
        page.addView(label("TOOLS", 11f, Theme.CYAN, true))
        page.addView(label("A workspace for getting things done.", 25f, Theme.TEXT, true).apply { setPadding(0, dp(8), 0, dp(5)) })
        page.addView(label("Tools are separated from chat so the conversation stays focused.", 13f, Theme.MUTED))
        gap(page, action("Capability report", "Live local checks for agent, screen access, tools, packs, providers and voice", true) {
            startActivity(Intent(this, OrionCapabilityReportActivity::class.java))
        }, 12)
        gap(page, action("Ask Orion to do it", "Describe your goal; Orion can choose the right available tools", true) {
            startActivity(Intent(this, MainChatActivity::class.java))
        }, 15)
        val device = section(page, "Phone & diagnostics", "Local utilities for your device")
        gap(device, action("Network Radar · Game Sentinel", "Connectivity, thermal, memory, and gaming-session diagnostics", true) {
            startActivity(Intent(this, DeviceUtilitiesActivity::class.java))
        }, 8)
        val agent = section(page, "Agent workspace", "Capabilities that help Orion research and work")
        gap(agent, action("Task centre", "Manage task status and resume saved agent sessions", true) {
            startActivity(Intent(this, TaskCenterActivity::class.java))
        }, 8)
        gap(agent, action("Teach Mode · Routine recorder", "Record complex taps, swipes and waits once; replay locally without AI", true) {
            startActivity(Intent(this, TeachModeActivity::class.java))
        }, 8)
        gap(agent, action("Skills studio", "Manage enabled skills and their permissions") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "skills"))
        }, 8)
        gap(agent, action("Memory & routines", "Browse, search, edit, and delete Orion’s local memories") {
            startActivity(Intent(this, MemoryActivity::class.java))
        }, 8)
        gap(agent, action("Open Orion Browser", "Search the web inside Orion; the screen agent can interact with accessible page elements", true) {
            startActivity(Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_URL, "https://duckduckgo.com/"))
        }, 8)
        gap(agent, action("Screen recording", "Start an Android-approved screen recording") {
            startActivity(Intent(this, RecordRequestActivity::class.java))
        }, 8)
        val integrations = section(page, "Connected services", "Provider keys and integrations are managed in Settings")
        gap(integrations, action("Configure services", "AI providers, Telegram, MCP, voice, and quota controls") {
            showTab("Settings")
        }, 8)

        val catalog = section(
            page,
            "Available agent tools",
            "READ/WRITE labels are generated from the same registry used by the agent prompt and scope guard."
        )
        ToolRegistry.all.forEach { definition ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = surface(13)
            }
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            details.addView(label(definition.name, 12f, Theme.TEXT, true))
            details.addView(label(definition.description, 11f, Theme.MUTED).apply {
                maxLines = 3
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val isRead = definition.access == ToolAccess.READ
            val badge = label(definition.access.name, 9f, if (isRead) Theme.CYAN else Theme.WARN, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(5), dp(8), dp(5))
                background = GradientDrawable().apply {
                    setColor(if (isRead) 0xFF172D28.toInt() else 0xFF302820.toInt())
                    cornerRadius = dp(9).toFloat()
                    setStroke(dp(1), if (isRead) Theme.VIOLET else 0xFF5C4630.toInt())
                }
            }
            row.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            gap(catalog, row, 6)
        }
    }

    private fun buildSettings(page: LinearLayout) {
        page.addView(label("SETTINGS", 11f, Theme.CYAN, true))
        page.addView(label("Make Orion yours.", 26f, Theme.TEXT, true).apply { setPadding(0, dp(8), 0, dp(5)) })
        page.addView(label("Organized controls, with sensitive settings kept out of the chat.", 13f, Theme.MUTED))
        gap(page, action("Ask Orion", "Return to the central AI agent without losing your settings", true) {
            startActivity(Intent(this, MainChatActivity::class.java))
        }, 15)
        gap(page, action("AI providers & models", "API keys, model selection, vision, quota saving") {
            openSettingsSection("providers")
        }, 15)
        gap(page, action("Voice & character", "Voice, speech speed, avatar, and personality") {
            openSettingsSection("voice")
        })
        gap(page, action("Memory & routines", "Search and manage Orion’s saved local memories") {
            startActivity(Intent(this, MemoryActivity::class.java))
        })
        gap(page, action("Integrations", "Telegram, remote MCP, connection status") {
            openSettingsSection("integrations")
        })
        gap(page, action("Permissions & diagnostics", "Accessibility, microphone, network and device tools") {
            openSettingsSection(null)
        })
        gap(page, action("Open full-screen chat", "A separate conversation from the quick-command panel") {
            startActivity(Intent(this, MainChatActivity::class.java))
        })
        gap(page, action("Open Android accessibility settings", "Required for on-screen phone control") {
            AccessibilityDisclosure.requestSettings(this)
        })
        page.addView(label("Orion asks before consequential actions. API keys stay on-device and are not shown in chat.", 12f, Theme.MUTED).apply {
            setPadding(0, dp(20), 0, 0)
        })
    }

    private fun buildStatusText(): String {
        val service = PhoneAiService.instance
        val access = if (service != null) "Accessibility agent active" else "Accessibility agent not enabled"
        val keys = Providers.all.count { Prefs.hasKey(this, it.id) }
        return "$access  ·  $keys AI provider key(s) configured"
    }

    private fun refreshStatus() {
        composeStatus?.value = buildStatusText()
        if (::statusLabel.isInitialized) statusLabel.text = buildStatusText()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
}
