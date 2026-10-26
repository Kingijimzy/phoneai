package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicInteger

/** Optional bridge to the user's installed Termux RUN_COMMAND service. This is not root access. */
class TermuxBridgeActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var command: EditText
    private lateinit var enabledSwitch: Switch
    private val prefs by lazy { getSharedPreferences("phoneai", MODE_PRIVATE) }
    private var commandsThisSession = 0
    private var sessionStartedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        sessionStartedAt = System.currentTimeMillis()
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Theme.BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        addText("ORION / TERMUX BRIDGE", 10f, Theme.MUTED, true, 0)
        addText("External terminal · explicit approval", 24f, Theme.TEXT, true, 4)
        addText(
            "This bridge calls Termux's documented RUN_COMMAND service. It runs as Termux's app user, not root. Commands can access Termux files and installed packages, so every command is checked against Orion's approval mode and task budget.",
            13f, Theme.MUTED, false, 8
        )
        addButton("‹ Back to Agent Control") { finish() }

        section("BRIDGE STATE")
        enabledSwitch = Switch(this).apply {
            text = "Enable Termux bridge"
            setTextColor(Theme.TEXT)
            isChecked = prefs.getBoolean("termux_bridge_enabled", false) &&
                AgentPolicy.enabled(this@TermuxBridgeActivity, "termux_bridge")
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("termux_bridge_enabled", checked).apply()
                AgentPolicy.setEnabled(this@TermuxBridgeActivity, "termux_bridge", checked)
                AgentPolicy.log(this@TermuxBridgeActivity, "termux_bridge", "toggle", true,
                    if (checked) "user enabled Termux bridge; runtime setup still checked per command" else "user disabled Termux bridge")
                refreshStatus()
            }
        }
        root.addView(enabledSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        status = addText("", 13f, Theme.TEXT, false, 6)
        addButton("Open Termux") { openTermux() }
        addButton("Open PhoneAI app permissions") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        addText(
            "One-time Termux setup: open Termux and set allow-external-apps = true in ~/.termux/termux.properties. Also grant PhoneAI the additional “Run commands in Termux environment” permission in Android App Info. Orion cannot silently grant either permission.",
            12f, Theme.MUTED, false, 8
        )

        section("COMMAND")
        command = EditText(this).apply {
            hint = "Enter a Termux command (max 2,048 characters)"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            setSingleLine(false)
            minLines = 2
            maxLines = 5
            background = GradientDrawable().apply {
                setColor(0xFF171717.toInt())
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF3A3A3A.toInt())
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(command, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        addText("Examples: pwd · ls -la · git status · python --version. Commands outside the exact read-only set are treated as dangerous and require explicit confirmation unless a valid trusted session covers this pack.", 12f, Theme.MUTED, false, 5)
        addButton("RUN COMMAND", true) { requestCommand() }
        addButton("Refresh last result") { refreshOutput() }

        section("LAST RESULT")
        output = addText("No Termux result received in this app session.", 12f, Theme.TEXT, false, 5)
        addText("Results are held in memory and are not written to the audit log. The audit log stores only command dispatch state, exit status and output length.", 11f, Theme.MUTED, false, 5)
        refreshStatus()
        refreshOutput()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshOutput()
    }

    private fun isInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo("com.termux", 0)
        true
    } catch (_: Exception) { false }

    private fun hasRunCommandPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 23) checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
        else true

    private fun refreshStatus() {
        val installed = isInstalled()
        val enabled = prefs.getBoolean("termux_bridge_enabled", false) &&
            AgentPolicy.enabled(this, "termux_bridge")
        val permission = hasRunCommandPermission()
        status.text = buildString {
            append("Termux app: ").append(if (installed) "installed" else "not installed").append("\n")
            append("Bridge pack: ").append(if (enabled) "enabled" else "OFF").append("\n")
            append("RUN_COMMAND permission: ").append(if (permission) "granted" else "not granted").append("\n")
            append("Approval mode: ").append(AgentPolicy.mode(this@TermuxBridgeActivity).label).append("\n")
            append("Session budget: ").append(commandsThisSession).append("/")
                .append(minOf(AgentPolicy.maxCalls(this@TermuxBridgeActivity), AgentPolicy.maxSteps(this@TermuxBridgeActivity)))
                .append(" commands · ").append(AgentPolicy.maxMinutes(this@TermuxBridgeActivity)).append(" min")
            if (!installed) append("\nInstall Termux first; the bridge will stay unavailable until the app is present.")
            if (installed && !permission) append("\nGrant PhoneAI the additional Run commands in Termux environment permission in App Info.")
            if (installed && permission && enabled) append("\nTermux must also have allow-external-apps enabled in termux.properties.")
        }
    }

    private fun requestCommand() {
        val text = command.text.toString().trim()
        if (text.isBlank()) { toast("Enter a command first."); return }
        if (text.length > 2048) { toast("Command is too long (maximum 2,048 characters)."); return }
        if (!isInstalled()) { toast("Termux is not installed; no command was sent."); refreshStatus(); return }
        if (!prefs.getBoolean("termux_bridge_enabled", false) || !AgentPolicy.enabled(this, "termux_bridge")) {
            toast("Enable the Termux bridge first."); return
        }
        if (!hasRunCommandPermission()) {
            AlertDialog.Builder(this).setTitle("Termux permission required")
                .setMessage("Android has not granted PhoneAI permission to run commands in Termux. Open App Info and grant the additional permission first. No command was sent.")
                .setPositiveButton("OPEN APP INFO") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.setNegativeButton("CANCEL", null).show()
            return
        }
        val maxCommands = minOf(AgentPolicy.maxCalls(this), AgentPolicy.maxSteps(this))
        if (commandsThisSession >= maxCommands) {
            toast("Termux bridge session budget reached. Close and reopen the bridge for a new session."); return
        }
        if (System.currentTimeMillis() - sessionStartedAt >= AgentPolicy.maxMinutes(this) * 60_000L) {
            toast("Termux bridge session time budget reached. Close and reopen the bridge for a new session."); return
        }
        val decision = AgentPolicy.evaluate(
            this, "termux_bridge", "termux_command",
            dangerous = !TermuxCommandPolicy.isReadOnly(text)
        )
        when (decision.decision) {
            AgentPolicy.Decision.DENY, AgentPolicy.Decision.PLAN_ONLY -> {
                AgentPolicy.log(this, "termux_command", "dispatch", false, decision.reason)
                toast("BLOCKED: ${decision.reason}")
            }
            AgentPolicy.Decision.NEED_APPROVAL -> AlertDialog.Builder(this)
                .setTitle("Approve Termux command?")
                .setMessage("Command will run in Termux's user context, not root.\n\n${text.take(1800)}\n\nApproval mode: ${AgentPolicy.mode(this).label}. Proceed once?")
                .setNegativeButton("CANCEL") { _, _ ->
                    AgentPolicy.log(this, "termux_command", "dispatch", false, "user declined; command not sent")
                }
                .setPositiveButton("APPROVE & RUN") { _, _ ->
                    AgentPolicy.log(this, "termux_command", "dispatch", true, "user approved Termux command; command text omitted from log")
                    dispatchCommand(text)
                }.show()
            AgentPolicy.Decision.ALLOW -> {
                AgentPolicy.log(this, "termux_command", "dispatch", null, "allowed by selected approval policy")
                dispatchCommand(text)
            }
        }
    }

    private fun dispatchCommand(text: String) {
        val requestCode = nextRequestCode()
        val callback = Intent(this, TermuxBridgeResultReceiver::class.java)
            .putExtra(TermuxBridgeResultReceiver.EXTRA_REQUEST_ID, requestCode)
        val pendingFlags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val resultPendingIntent = PendingIntent.getBroadcast(this, requestCode, callback, pendingFlags)
        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", text))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Orion approved command")
            putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Command approved in Orion's Termux bridge.")
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPendingIntent)
        }
        commandsThisSession++
        TermuxBridgeResultStore.lastResult = "Command dispatched to Termux. Waiting for Termux's result callback; execution is not confirmed yet."
        TermuxBridgeResultStore.lastExitCode = null
        refreshOutput()
        refreshStatus()
        try {
            startService(intent)
            AgentPolicy.log(this, "termux_command", "dispatch-result", null, "Android accepted Termux service start; awaiting Termux result callback")
        } catch (e: SecurityException) {
            TermuxBridgeResultStore.lastResult = "UNVERIFIED: Termux rejected the request. Check PhoneAI's Run commands permission and Termux's allow-external-apps setting. No successful execution is claimed."
            AgentPolicy.log(this, "termux_command", "dispatch-result", false, "Termux permission or external-app policy rejected service start")
            refreshOutput()
        } catch (e: Exception) {
            TermuxBridgeResultStore.lastResult = "FAILED TO DISPATCH: ${e.javaClass.simpleName}. No command result was received."
            AgentPolicy.log(this, "termux_command", "dispatch-result", false, "service start failed: ${e.javaClass.simpleName}")
            refreshOutput()
        }
    }

    private fun refreshOutput() {
        if (!::output.isInitialized) return
        output.text = TermuxBridgeResultStore.lastResult
            ?: "No Termux result received in this app session."
    }

    private fun openTermux() {
        val launch = packageManager.getLaunchIntentForPackage("com.termux")
        if (launch != null) startActivity(launch)
        else {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app"))) }
            catch (_: Exception) { toast("Termux is not installed.") }
        }
    }

    private fun addText(text: String, size: Float, color: Int, bold: Boolean, top: Int): TextView {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        return view
    }

    private fun section(text: String) {
        addText(text, 10f, Theme.MUTED, true, 18).letterSpacing = .12f
    }

    private fun addButton(text: String, primary: Boolean = false, action: () -> Unit): Button {
        val button = Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Theme.TEXT)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (primary) 0xFF424242.toInt() else 0xFF222222.toInt()
            )
            setOnClickListener { action() }
        }
        root.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return button
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private val requestCounter = AtomicInteger(1000)
        fun nextRequestCode(): Int = requestCounter.getAndIncrement()
    }
}

/** Exact, deliberately small allowlist of harmless diagnostics; everything else is dangerous. */
object TermuxCommandPolicy {
    private val readOnly = setOf(
        "pwd", "ls", "ls -la", "git status", "git status --short",
        "git branch --show-current", "git log -5 --oneline", "git --version",
        "python --version", "python3 --version", "node --version",
        "termux-info", "whoami", "uname -a", "df -h", "free -h", "date"
    )
    fun isReadOnly(command: String): Boolean = command.trim() in readOnly
}

/** Result state is intentionally in-memory; command output is not persisted or written to audit logs. */
object TermuxBridgeResultStore {
    @Volatile var lastResult: String? = null
    @Volatile var lastExitCode: Int? = null
}

class TermuxBridgeResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent) {
        val extras = intent.extras
        val bundle = intent.getBundleExtra("com.termux.app.extra.PLUGIN_RESULT_BUNDLE")
            ?: extras?.keySet()?.mapNotNull { key -> extras.get(key) as? Bundle }
                ?.firstOrNull { it.containsKey("stdout") || it.containsKey("exitCode") || it.containsKey("errmsg") }
        if (bundle == null) {
            TermuxBridgeResultStore.lastResult = "UNVERIFIED: Termux sent a callback without a readable result bundle."
            AgentPolicy.log(context, "termux_command", "result", false, "callback received without result bundle")
            return
        }
        val stdout = bundle.getString("stdout", "")
        val stderr = bundle.getString("stderr", "")
        val exitCode = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else Int.MIN_VALUE
        val err = bundle.getInt("err", -1)
        val errMsg = bundle.getString("errmsg", "")
        TermuxBridgeResultStore.lastExitCode = exitCode.takeIf { it != Int.MIN_VALUE }
        val resultText = buildString {
            append(if (err != -1) "TERMUX SERVICE ERROR" else if (exitCode == 0) "EXIT 0 · SUCCESS" else if (exitCode != Int.MIN_VALUE) "EXIT $exitCode · FAILED" else "UNVERIFIED · EXIT STATUS MISSING")
            if (errMsg.isNotBlank()) append("\n").append(errMsg.take(1000))
            if (stdout.isNotBlank()) append("\n\nSTDOUT\n").append(stdout.take(8000))
            if (stderr.isNotBlank()) append("\n\nSTDERR\n").append(stderr.take(3000))
            if (stdout.isBlank() && stderr.isBlank() && errMsg.isBlank()) append("\nTermux returned no output.")
        }
        TermuxBridgeResultStore.lastResult = resultText
        val confirmed = err == -1 && exitCode == 0
        AgentPolicy.log(
            context, "termux_command", "result", confirmed,
            if (exitCode == Int.MIN_VALUE) "Termux callback received but exit status missing; output length ${stdout.length + stderr.length}"
            else "Termux returned exit code $exitCode; output length ${stdout.length + stderr.length}; output omitted from audit log"
        )
    }
}
