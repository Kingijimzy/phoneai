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
    private lateinit var commandModelStatus: TextView

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
        addButton("Configure tool allowlist") { configureAllowlist() }
        addButton("Propose a new skill") { proposeSkill() }
        addButton("Review / revoke skills") { reviewSkills() }
        addText("Talk and live voice use your normal chat provider. Command planning and screen-control reasoning can use a separate saved text provider/model. No model-list or test request is made when saving this setting.", 12f, Theme.MUTED, false, 4)
        commandModelStatus = addText(commandModelSummary(), 12f, Theme.TEXT, false, 4)
        addButton("Configure Command provider / model") { configureCommandModel() }
        addButton("Use normal chat model for Command") {
            Prefs.clearCommandProvider(this)
            AgentPolicy.log(this, "model-routing", "clear-command-override", true, "Command now inherits normal chat provider/model")
            refresh()
            Toast.makeText(this, "Command now uses the normal chat model.", Toast.LENGTH_SHORT).show()
        }

        section("SELF-TEST")
        testReport = addText("Not run yet.", 12f, Theme.MUTED, false, 4)
        addButton("OPEN WORKSPACE TERMINAL") { startActivity(Intent(this, WorkspaceShellActivity::class.java)) }
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
    private fun commandModelSummary(): String {
        val id = Prefs.commandProviderId(this)
        val provider = Providers.find(id)
        return if (provider == null) {
            val normal = Prefs.provider(this, false)
            "Command model: inherits normal chat selection (${normal.label} / ${Prefs.model(this, normal, false)})."
        } else {
            "Command model override: ${provider.label} / ${Prefs.commandModelId(this) ?: provider.textModel}. Talk and live voice remain on the normal chat selection."
        }
    }

    private fun configureCommandModel() {
        val choices = Providers.all.filter { provider ->
            (Prefs.hasKey(this, provider.id) || provider.noKey) &&
                (!provider.requiresPaidOptIn || Prefs.allowPaidProviders(this))
        }
        if (choices.isEmpty()) {
            AlertDialog.Builder(this).setTitle("No available Command provider")
                .setMessage("Add a provider key in Settings first. Saving a model override does not test the provider or spend an API request.")
                .setPositiveButton("OK", null).show()
            return
        }
        val currentId = Prefs.commandProviderId(this) ?: Prefs.provider(this, false).id
        val selectedIndex = choices.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Command text provider")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), selectedIndex) { dialog, index ->
                val provider = choices[index]
                val existing = if (Prefs.commandProviderId(this) == provider.id) {
                    Prefs.commandModelId(this) ?: provider.textModel
                } else Prefs.model(this, provider, false)
                val modelInput = android.widget.EditText(this).apply {
                    hint = "Model ID (provider's exact model identifier)"
                    setText(existing)
                    setSingleLine(true)
                    selectAll()
                }
                AlertDialog.Builder(this).setTitle("Command model · ${provider.label}")
                    .setMessage("Use an exact text-chat model ID. This is saved locally; Orion will not call /models or send a test request here. Paid/metered providers remain blocked unless explicitly enabled in Settings.")
                    .setView(modelInput)
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("SAVE") { _, _ ->
                        val model = modelInput.text.toString().trim()
                        if (model.isBlank()) {
                            Toast.makeText(this, "Model ID cannot be empty.", Toast.LENGTH_LONG).show()
                        } else {
                            Prefs.setCommandProvider(this, provider.id, model)
                            AgentPolicy.log(this, "model-routing", "set-command-override", true, "Command model override saved for ${provider.id}; model id stored")
                            refresh()
                            Toast.makeText(this, "Command model saved. No provider test request was sent.", Toast.LENGTH_LONG).show()
                        }
                    }.show()
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null).show()
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

    private fun configureAllowlist() {
        val available = AgentPolicy.packs.filter { it.implemented && AgentPolicy.enabled(this, it.id) }
        if (available.isEmpty()) {
            Toast.makeText(this, "Enable a supported capability pack first.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Choose pack for allowlist")
            .setItems(available.map { it.title }.toTypedArray()) { _, index ->
                val pack = available[index]
                val input = android.widget.EditText(this).apply {
                    hint = "Exact tool IDs separated by commas"
                    setText(AgentPolicy.allowlist(this@AgentControlActivity, pack.id).joinToString(", "))
                    setSingleLine(false)
                }
                AlertDialog.Builder(this).setTitle("Allowlisted tools · ${pack.title}")
                    .setMessage("Use exact registered tool IDs. Unknown tools are denied. In Allowlist mode, only these tools may run automatically; consequential actions still need approval unless a trusted session covers the pack.")
                    .setView(input).setNegativeButton("CANCEL", null)
                    .setPositiveButton("SAVE") { _, _ ->
                        AgentPolicy.setAllowlist(this, pack.id, input.text.toString().split(",").map { it.trim() })
                        AgentPolicy.log(this, "allowlist", "update", true, "allowlist updated for pack ${pack.id}")
                        Toast.makeText(this, "Allowlist saved.", Toast.LENGTH_SHORT).show()
                    }.show()
            }.show()
    }

    private fun proposeSkill() {
        if (!AgentPolicy.enabled(this, "self_evolution")) {
            AlertDialog.Builder(this).setTitle("Self-evolution is disabled")
                .setMessage("Enable the Self-evolution capability pack first. New skills remain disabled until you separately approve them.")
                .setPositiveButton("OK", null).show()
            return
        }
        val id = android.widget.EditText(this).apply { hint = "Skill ID (lowercase, e.g. focus_helper)" }
        val version = android.widget.EditText(this).apply { hint = "Version (MAJOR.MINOR.PATCH)"; setText("1.0.0") }
        val purpose = android.widget.EditText(this).apply { hint = "Purpose and expected behavior"; minLines = 2; maxLines = 4 }
        val toolIds = android.widget.EditText(this).apply { hint = "Existing tool IDs, comma-separated"; minLines = 2; maxLines = 4 }
        val calls = android.widget.EditText(this).apply { hint = "Maximum calls (1–20)"; setText("4"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val seconds = android.widget.EditText(this).apply { hint = "Time budget in seconds (1–120)"; setText("30"); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(4))
            listOf(id, version, purpose, toolIds, calls, seconds).forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Propose skill · not enabled")
            .setMessage("A skill is metadata referencing existing tools only. No arbitrary code, unrestricted shell, or new Android permissions. Static checks run before saving.")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("VALIDATE & PROPOSE") { _, _ ->
                val candidate = PhoneAiSkill(
                    id = id.text.toString().trim(),
                    version = version.text.toString().trim(),
                    description = purpose.text.toString().trim(),
                    allowedTools = toolIds.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                    maxCalls = calls.text.toString().toIntOrNull() ?: -1,
                    maxDurationMs = (seconds.text.toString().toLongOrNull() ?: -1L) * 1000L,
                    requiresApproval = true,
                    enabled = false
                )
                val check = SkillRegistry.validate(candidate)
                if (!check.allowed) {
                    AgentPolicy.log(this, "skill-proposal", "static-check", false, "rejected: ${check.reason}")
                    AlertDialog.Builder(this).setTitle("Skill rejected").setMessage(check.reason).setPositiveButton("OK", null).show()
                    return@setPositiveButton
                }
                val registered = SkillRegistry.register(this, candidate)
                AgentPolicy.log(this, "skill-proposal", "register", registered.allowed, if (registered.allowed) "validated skill saved disabled" else registered.reason)
                if (!registered.allowed) {
                    AlertDialog.Builder(this).setTitle("Could not save proposal").setMessage(registered.reason).setPositiveButton("OK", null).show()
                    return@setPositiveButton
                }
                AlertDialog.Builder(this).setTitle("Review skill proposal")
                    .setMessage("ID: ${candidate.id}\nVersion: ${candidate.version}\nPurpose: ${candidate.description}\nTools: ${candidate.allowedTools.sorted().joinToString(", ")}\nBudget: ${candidate.maxCalls} calls / ${candidate.maxDurationMs / 1000}s\nRisk: approval required; only registered tools; disabled by default.\n\nEnable this skill now?")
                    .setNegativeButton("KEEP DISABLED") { _, _ -> AgentPolicy.log(this, "skill:${candidate.id}", "enable", false, "user kept proposed skill disabled") }
                    .setPositiveButton("APPROVE & ENABLE") { _, _ ->
                        val enabled = SkillRegistry.setEnabled(this, candidate.id, true)
                        AgentPolicy.log(this, "skill:${candidate.id}", "enable", enabled.allowed, if (enabled.allowed) "user approved skill enablement" else enabled.reason)
                        Toast.makeText(this, if (enabled.allowed) "Skill enabled. You can revoke it here." else enabled.reason, Toast.LENGTH_LONG).show()
                    }.show()
            }.show()
    }

    private fun reviewSkills() {
        val skills = SkillRegistry.list(this)
        if (skills.isEmpty()) {
            AlertDialog.Builder(this).setMessage("No skills registered.").setPositiveButton("OK", null).show()
            return
        }
        val labels = skills.map { "${if (it.enabled) "ON" else "OFF"} · ${it.id} · ${it.allowedTools.size} tools" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Review or revoke skills")
            .setItems(labels) { _, index ->
                val skill = skills[index]
                val details = "ID: ${skill.id}\nVersion: ${skill.version}\nPurpose: ${skill.description}\nTools: ${skill.allowedTools.sorted().joinToString(", ")}\nBudget: ${skill.maxCalls} calls / ${skill.maxDurationMs / 1000}s\nApproval required: ${skill.requiresApproval}\nEnabled: ${skill.enabled}"
                val builder = AlertDialog.Builder(this).setTitle(skill.id).setMessage(details).setNegativeButton("CLOSE", null)
                if (skill.enabled) {
                    builder.setPositiveButton("REVOKE / DISABLE") { _, _ ->
                        val result = SkillRegistry.setEnabled(this, skill.id, false)
                        AgentPolicy.log(this, "skill:${skill.id}", "revoke", result.allowed, if (result.allowed) "user revoked skill" else result.reason)
                        Toast.makeText(this, if (result.allowed) "Skill disabled." else result.reason, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (AgentPolicy.enabled(this, "self_evolution")) {
                        builder.setPositiveButton("APPROVE & ENABLE") { _, _ ->
                            val result = SkillRegistry.setEnabled(this, skill.id, true)
                            AgentPolicy.log(this, "skill:${skill.id}", "enable", result.allowed, if (result.allowed) "user approved skill enablement" else result.reason)
                            Toast.makeText(this, if (result.allowed) "Skill enabled." else result.reason, Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (!skill.id.startsWith("whatsapp_draft") && !skill.id.startsWith("email_draft")) {
                        builder.setNeutralButton("REMOVE") { _, _ ->
                            val result = SkillRegistry.remove(this, skill.id)
                            AgentPolicy.log(this, "skill:${skill.id}", "remove", result.allowed, if (result.allowed) "user removed skill" else result.reason)
                            Toast.makeText(this, if (result.allowed) "Skill removed." else result.reason, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                builder.show()
            }.show()
    }

    private fun runSelfTest() {
        if (!AgentPolicy.enabled(this, "self_test")) {
            testReport.text = "BLOCKED · Enable the Self-test capability pack first."
            Toast.makeText(this, "Enable the Self-test pack first.", Toast.LENGTH_LONG).show()
            return
        }
        val policy = AgentPolicy.evaluate(this, "self_test", "self_test", dangerous = false)
        when (policy.decision) {
            AgentPolicy.Decision.DENY, AgentPolicy.Decision.PLAN_ONLY -> {
                testReport.text = "BLOCKED · ${policy.reason}"
                AgentPolicy.log(this, "self_test", "run", false, policy.reason)
                return
            }
            AgentPolicy.Decision.NEED_APPROVAL -> {
                AlertDialog.Builder(this).setTitle("Approve self-test?")
                    .setMessage("This test makes one selected-provider probe if a key is saved, plus public weather, news and sports lookups. It does not perform phone actions or trading orders. Provider probe may consume API quota.")
                    .setNegativeButton("CANCEL") { _, _ -> AgentPolicy.log(this, "self_test", "run", false, "user cancelled diagnostic") }
                    .setPositiveButton("RUN TESTS") { _, _ -> executeSelfTest() }.show()
            }
            AgentPolicy.Decision.ALLOW -> executeSelfTest()
        }
    }

    private fun executeSelfTest() {
        testReport.text = "Running local checks and bounded public-data probes…"
        Thread {
            val lines = mutableListOf<String>()
            fun test(label: String, block: () -> String) {
                try {
                    val result = block()
                    val pass = result.startsWith("PASS:")
                    lines += "${if (pass) "PASS" else if (result.startsWith("SKIP:")) "INFO" else "FAIL"} · $label — ${result.substringAfter(":").trim().take(220)}"
                } catch (e: Exception) {
                    lines += "FAIL · $label — ${e.message ?: e.javaClass.simpleName}"
                }
            }
            val p = Prefs.provider(this, false)
            test("Selected AI provider") {
                if (!Prefs.hasKey(this, p.id) && !p.noKey) "SKIP: ${p.label} has no saved API key"
                else {
                    val error = Nim(this).testModel(p, Prefs.model(this, p, false), false)
                    if (error.isBlank()) "PASS: ${p.label} returned a usable response"
                    else "FAIL: ${p.label} probe failed: ${error.take(170)}"
                }
            }
            test("Weather data") {
                val value = WeatherService.current("Lagos")
                if (value.isNotBlank() && !value.startsWith("error", true) && !value.contains("unavailable", true)) "PASS: weather service returned data"
                else "FAIL: ${value.take(170)}"
            }
            test("News data") {
                val value = LiveNews.search("latest public news")
                if (value.isNotBlank() && !value.contains("search failed", true) && !value.contains("no results", true)) "PASS: news endpoint returned a response"
                else "FAIL: ${value.take(170)}"
            }
            test("Sports lookup") {
                val value = LiveWebSearch.search("latest football sports scores")
                if (value.isNotBlank() && !value.contains("search failed", true) && !value.contains("no results", true)) "PASS: public sports search returned a response"
                else "FAIL: ${value.take(170)}"
            }
            test("Sample phone reads") {
                val service = PhoneAiService.instance
                if (service == null) "SKIP: Orion accessibility service is not active"
                else {
                    val tools = Tools(service)
                    val time = tools.time()
                    val battery = tools.battery()
                    if (time.isNotBlank() && battery.isNotBlank() && !battery.startsWith("error", true)) "PASS: local time and battery read; no phone action performed"
                    else "FAIL: time/battery read unavailable"
                }
            }
            test("Accessibility permission") {
                val enabled = runCatching {
                    Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains(packageName, true)
                }.getOrDefault(false)
                if (enabled) "PASS: Orion accessibility service is enabled" else "FAIL: Orion accessibility service is not enabled"
            }
            test("Termux bridge state") {
                val installed = runCatching { packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)
                if (!installed) "SKIP: Termux not installed; bridge remains off"
                else "PASS: Termux is installed; bridge remains off until separately enabled"
            }
            test("Trading safety") { "PASS: order-like tools are denied by the read-only Trading research pack" }
            test("Approval policy") { "PASS: current mode is ${AgentPolicy.mode(this).label}; tools require enabled packs" }
            val report = lines.joinToString("\n")
            AgentPolicy.log(this, "self_test", "run", true, "diagnostic completed: ${lines.count { it.startsWith("PASS") }} pass, ${lines.count { it.startsWith("FAIL") }} fail, ${lines.count { it.startsWith("INFO") }} skipped/info")
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    testReport.text = report
                    AlertDialog.Builder(this).setTitle("Self-test report").setMessage(report)
                        .setPositiveButton("CLOSE", null).show()
                }
            }
        }.start()
    }
    private fun showLog() {
        val text = AgentPolicy.recentLogs(this).ifEmpty { listOf("No events logged yet.") }.joinToString("\n\n")
        AlertDialog.Builder(this).setTitle("Recent action log").setMessage(text).setPositiveButton("CLOSE", null).show()
    }
    private fun refresh() {
        modeStatus.text = "Mode: ${AgentPolicy.mode(this).label} · default is Ask\nTrusted until: ${if (AgentPolicy.trustedUntil(this) > System.currentTimeMillis()) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(AgentPolicy.trustedUntil(this))) else "not active"}"
        packStatus.text = "Enabled: ${AgentPolicy.packs.count { AgentPolicy.enabled(this, it.id) }} · unsupported packs remain off. Only enabled packs should be dispatchable."
        commandModelStatus.text = commandModelSummary()
    }
}
