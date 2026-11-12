package com.phoneai.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Orion entry point. The Compose shell owns all home/navigation UI; destination
 * activities own their feature surfaces. No hidden legacy home tree is built.
 */
class MainActivity : ComponentActivity() {
    companion object {
        @Volatile private var crashDiagnosticsInstalled = false
    }

    private var composeStatus: MutableState<String>? = null

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
                    onOpenTrading = { launchDestination(TradingDeskActivity.intent(this@MainActivity), "Trading Desk") },
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

    /** Route each settings shortcut to the screen that owns that domain. */
    private fun openSettingsSection(section: String?) {
        val destination: Class<out android.app.Activity>
        val intent: Intent
        when (section) {
            null -> {
                destination = SettingsFeatureActivity::class.java
                intent = Intent(this, destination).putExtra(SettingsFeatureActivity.EXTRA_SECTION, "all")
            }
            "providers", "voice", "character", "permissions", "recording" -> {
                destination = SettingsFeatureActivity::class.java
                intent = Intent(this, destination).putExtra(SettingsFeatureActivity.EXTRA_SECTION, section)
            }
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
            "trading" -> { startActivity(TradingDeskActivity.intent(this)); return }
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
            .setMessage("Orion closed unexpectedly last time. Copy this report and send it here so I can identify the exact cause. The report stays on your phone until you choose to copy it.\n\n" + report.take(1800))
            .setPositiveButton("Copy report") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Orion crash report", report))
                Toast.makeText(this, "Crash report copied. Paste it into this chat.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun buildStatusText(): String {
        val service = PhoneAiService.instance
        val access = if (service != null) "Accessibility agent active" else "Accessibility agent not enabled"
        val keys = Providers.all.count { Prefs.hasKey(this, it.id) }
        return "$access  ·  $keys AI provider key(s) configured"
    }

    private fun refreshStatus() {
        composeStatus?.value = buildStatusText()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
}
