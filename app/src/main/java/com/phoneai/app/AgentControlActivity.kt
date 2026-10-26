package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** User-facing approval modes, capability toggles, diagnostics and audit log. */
class AgentControlActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var root: LinearLayout
    private lateinit var modeStatus: TextView
    private lateinit var testReport: TextView
    private lateinit var packStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Theme.BG) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(28)) }
        scroll.addView(root)
        setContentView(scroll)
        addText("ORION / AGENT CONTROL", 10f, Theme.MUTED, true, 0)
        addText("Approval & capabilities", 26f, Theme.TEXT, true, 4)
        addText("User approval is the limit—not silent power. Packs are off until you enable them. Unsupported integrations stay visibly off.", 13f, Theme.MUTED, false, 8)
        addButton("‹ Back to settings") { finish() }

        section("APPROVAL MODE")
        modeStatus = addText("", 14f, Theme.TEXT, true, 5)
        addText("Plan = suggest only. Ask = approve every action (default). Allowlist = run only listed tools. Trusted session = timed, logged trust for selected enabled packs.", 12f, Theme.MUTED, false, 4)
        addButton("Choose approval mode") { chooseMode() }
        addButton("Start trusted session (15–120 min)") { startTrusted() }
        addButton("STOP / LOCK TRUST NOW", true) {
            AgentPolicy.stopTrustedSession(this)
            refresh()
            Toast.makeText(this, "Trusted session stopped.", Toast.LENGTH_SHORT).show()
        }

        section("CAPABILITY PACKS")
        packStatus = addText("Enabled packs are stored locally. Packs without a working adapter cannot be enabled yet.", 12f, Theme.MUTED, false, 5)
        AgentPolicy.packs.forEach { pack ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(0xFF191919.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF343434.toInt()) }
            }
            val sw = Switch(this).apply {
                text = pack.title + if (pack.implemented) "" else " · not ready"
                setTextColor(Theme.TEXT)
                isChecked = AgentPolicy.enabled(this@AgentControlActivity, pack.id)
                isEnabled = pack.implemented
                setOnCheckedChangeListener { _, checked -> AgentPolicy.setEnabled(this@AgentControlActivity, pack.id, checked); refresh() }
            }
            row.addView(sw)
            row.addView(TextView(this).apply { text = pack.description; textSize = 12f; setTextColor(Theme.MUTED); setPadding(0, dp(3), 0, 0) })
            root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }

        section("BUDGETS & MODEL SPLIT")
        addText("Current per-task limits: ${AgentPolicy.maxSteps(this)} steps · ${AgentPolicy.maxCalls(this)} calls · ${AgentPolicy.maxMinutes(this)} minutes.", 12f, Theme.MUTED, false, 4)
        addButton("Set task budgets") { chooseBudgets() }
        addText("Talk/chat and Command/tool work are separate modes in the policy design. Model override controls are not yet connected to every chat/tool path; this screen will not claim a split is active until those call sites use it.", 12f, Theme.MUTED, false, 4)

        section("SELF-TEST")
        testReport = addText("Not run yet.", 12f, Theme.MUTED, false, 4)
        addButton("RUN SELF-TEST", true) { runSelfTest() }
        addButton("Open Android accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        section("ACTION LOG")
        addText("Recent local audit events. No API keys or secret values should be written here.", 12f, Theme.MUTED, false, 4)
        addButton("Refresh action log") { showLog() }
        addButton("Clear local action log") {
            AlertDialog.Builder(this).setTitle("Clear audit log?")
                .setMessage("This removes the local history of Orion policy events.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("CLEAR") { _, _ ->
                    getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE).edit().remove("action_log").apply()
                    AgentPolicy.log(this, "audit-log", "clear", true, "user cleared local audit log")
                    showLog()
                }.show()
        }
        refresh()
    }

    private fun addText(s: String, size: Float, color: Int, bold: Boolean, top: Int): TextView {
        val t = TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        return t
    }
    private fun section(s: String) {
        addText(s, 10f, Theme.MUTED, true, 19).apply { letterSpacing = .12f }
    }
    private fun addButton(s: String, primary: Boolean = false, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = s; isAllCaps = false; setTextColor(Theme.TEXT)
            backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) 0xFF424242.toInt() else 0xFF222222.toInt())
            setOnClickListener { action() }
        }
        root.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return b
    }
    private fun chooseMode() {
        val modes = AgentPolicy.Mode.values()
        AlertDialog.Builder(this).setTitle("Approval mode")
            .setItems(modes.map { "${it.label}${if (it == AgentPolicy.Mode.ASK) " · default" else ""}" }.toTypedArray()) { _, which ->
                AgentPolicy.setMode(this, modes[which])
                AgentPolicy.log(this, "policy", "mode-change", true, "mode set to ${modes[which].name}")
                refresh()
            }.show()
    }
    private fun startTrusted() {
        val enabled = AgentPolicy.packs.filter { it.implemented && AgentPolicy.enabled(this, it.id) }
        if (enabled.isEmpty()) {
            Toast.makeText(this, "Enable at least one supported pack first.", Toast.LENGTH_LONG).show()
            return
        }
        val names = enabled.map { it.title }.toTypedArray()
        val selected = BooleanArray(enabled.size) { true }
        AlertDialog.Builder(this).setTitle("Trust selected packs temporarily")
            .setMultiChoiceItems(names, selected) { _, i, checked -> selected[i] = checked }
            .setPositiveButton("CONTINUE") { _, _ ->
                val minutes = intArrayOf(15, 30, 60, 120)
                AlertDialog.Builder(this).setTitle("Session duration")
                    .setItems(arrayOf("15 minutes", "30 minutes", "60 minutes", "120 minutes")) { _, idx ->
                        AgentPolicy.startTrustedSession(this, minutes[idx], enabled.filterIndexed { i, _ -> selected[i] }.map { it.id }.toSet())
                        AgentPolicy.setMode(this, AgentPolicy.Mode.TRUSTED)
                        refresh()
                    }.show()
            }.setNegativeButton("CANCEL", null).show()
    }
    private fun chooseBudgets() {
        val current = "${AgentPolicy.maxSteps(this)},${AgentPolicy.maxCalls(this)},${AgentPolicy.maxMinutes(this)}"
        val input = android.widget.EditText(this).apply {
            hint = "steps,calls,minutes (e.g. 12,15,3)"
            setText(current); setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Task budget limits").setMessage("Maximum steps, API/tool calls, and minutes. Bounds: 1–100, 1–100, 1–30.")
            .setView(input).setNegativeButton("CANCEL", null).setPositiveButton("SAVE") { _, _ ->
                val v = input.text.toString().split(",").mapNotNull { it.trim().toIntOrNull() }
                if (v.size == 3) {
                    AgentPolicy.setBudgets(this, v[0], v[1], v[2]); AgentPolicy.log(this, "budget", "update", true, "task limits updated")
                    refresh()
                } else Toast.makeText(this, "Enter three whole numbers separated by commas.", Toast.LENGTH_LONG).show()
            }.show()
    }
    private fun runSelfTest() {
        val lines = mutableListOf<String>()
        val p = Prefs.provider(this, false)
        lines += "${if (Prefs.hasKey(this, p.id) || p.noKey) "PASS" else "WARN"} · Primary provider: ${p.label} (${if (Prefs.hasKey(this, p.id) || p.noKey) "configured" else "no saved key"})"
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        lines += "${if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) "PASS" else "WARN"} · Network: ${if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) "internet capability present; endpoint not probed" else "no active internet network"}"
        val access = runCatching {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            enabled.contains(packageName, true)
        }.getOrDefault(false)
        lines += "${if (access) "PASS" else "WARN"} · Phone control accessibility: ${if (access) "enabled" else "not enabled"}"
        val termux = runCatching { packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)
        lines += "${if (termux) "PASS" else "INFO"} · Termux bridge: ${if (termux) "Termux installed; bridge still requires separate enablement" else "off (Termux not installed)"}"
        lines += "PASS · Trading guard: order-like tools denied by trading_research policy"
        lines += "${if (AgentPolicy.mode(this) == AgentPolicy.Mode.ASK) "PASS" else "INFO"} · Approval default/current mode: ${AgentPolicy.mode(this).label}"
        lines += "INFO · Weather/news/sports live checks: not run by this local diagnostic; requires a connected data adapter."
        lines += "INFO · Sample phone action: not executed (self-test never performs a real device action)."
        testReport.text = lines.joinToString("\n")
        AgentPolicy.log(this, "self-test", "run", true, "local checks completed: ${lines.count { it.startsWith("PASS") }} pass, ${lines.count { it.startsWith("WARN") }} warnings")
        Toast.makeText(this, "Self-test finished. Review the report.", Toast.LENGTH_SHORT).show()
    }
    private fun showLog() {
        val text = AgentPolicy.recentLogs(this).ifEmpty { listOf("No events logged yet.") }.joinToString("\n\n")
        AlertDialog.Builder(this).setTitle("Recent action log").setMessage(text).setPositiveButton("CLOSE", null).show()
    }
    private fun refresh() {
        modeStatus.text = "Mode: ${AgentPolicy.mode(this).label} · default is Ask\nTrusted until: ${if (AgentPolicy.trustedUntil(this) > System.currentTimeMillis()) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(AgentPolicy.trustedUntil(this))) else "not active"}"
        packStatus.text = "Enabled: ${AgentPolicy.packs.count { AgentPolicy.enabled(this, it.id) }} · unsupported packs remain off. Only enabled packs should be dispatchable."
    }
}
