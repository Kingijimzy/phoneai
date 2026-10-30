package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Owner-only control surface. Android's device credential is the owner proof; the activity
 * is non-exported and screenshots are blocked while it is visible.
 */
class OwnerControlCenterActivity : Activity() {
    companion object {
        private const val REQUEST_OWNER_AUTH = 9184
        private const val PREFS = "phoneai_owner"
        private const val AUTH_AT = "owner_auth_at"
        private const val SESSION_MS = 5 * 60 * 1000L
    }

    private lateinit var root: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        if (hasRecentOwnerAuth()) render() else authenticateOwner()
    }

    private fun hasRecentOwnerAuth(): Boolean {
        val at = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(AUTH_AT, 0L)
        return at > 0L && System.currentTimeMillis() - at in 0..SESSION_MS
    }

    private fun authenticateOwner() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            AlertDialog.Builder(this)
                .setTitle("Secure the phone first")
                .setMessage("Owner Control Centre is protected by your Android screen lock. Set a PIN, password, or pattern in Android Security settings, then open this panel again.")
                .setPositiveButton("OPEN SECURITY SETTINGS") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    finish()
                }
                .setNegativeButton("CLOSE") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        val prompt = keyguard.createConfirmDeviceCredentialIntent(
            "Verify Orion owner",
            "Unlock with your Android PIN, password, pattern, or supported biometric."
        )
        if (prompt == null) {
            Toast.makeText(this, "Android could not start owner verification.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(prompt, REQUEST_OWNER_AUTH)
    }

    @Deprecated("Android device-credential confirmation callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OWNER_AUTH) return
        if (resultCode == Activity.RESULT_OK) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(AUTH_AT, System.currentTimeMillis()).apply()
            render()
        } else {
            Toast.makeText(this, "Owner verification was not completed.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized && !hasRecentOwnerAuth()) finish()
    }

    private fun render() {
        if (::root.isInitialized) return
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        addText("ORION / OWNER ONLY", 10f, Theme.MUTED, true, 0)
        addText("Owner Control Centre", 27f, Theme.TEXT, true, 5)
        addText("Authenticated with the phone's device credential. Settings here control Orion's app-level behaviour; Android, provider and platform safeguards remain independently enforced.", 13f, Theme.MUTED, false, 8)
        addButton("‹ Back to Settings") { finish() }

        section("AUTONOMY & EXECUTION")
        val config = OrionAutonomySettings.read(this)
        addText("Mode: ${config.mode.name} · ${config.maxToolSteps} tool steps · ${config.maxAiRequests} AI requests · ${config.maxRetries} retries", 13f, Theme.TEXT, true, 5)
        addButton("Configure autonomy, limits and notifications", true) { showAutonomyControlCenter() }
        addButton("Configure individual tool Allow / Ask / Deny rules") { showToolRules() }
        addButton("Configure agent approval modes and capability packs") {
            startActivity(Intent(this, AgentControlActivity::class.java))
        }
        addButton("Open task and recovery centre") { startActivity(Intent(this, TaskCenterActivity::class.java)) }

        section("POLICY & RESTRICTION REGISTER")
        addText("This register describes Orion app-level gates and their current configurability. It does not expose external model-provider or operating-system internal policies.", 12f, Theme.MUTED, false, 4)
        addButton("Inspect every registered app policy and hard limit") { showPolicyRegister() }

        section("PRIVACY & SECRETS")
        addText(secretSummary(), 12f, Theme.TEXT, false, 4)
        addButton("Inspect / remove stored credentials") { showCredentialManager() }
        addButton("Add or rotate provider keys and integrations") { startActivity(Intent(this, SettingsActivity::class.java)) }
        addButton("View Android permission grants") { showPermissionStatus() }
        addButton("Review local action and policy audit") { showAudit() }
        addButton("Open memory and learning controls") { startActivity(Intent(this, MemoryActivity::class.java)) }
        addButton("Open Soul Engine and watcher status") { startActivity(Intent(this, SoulEngineActivity::class.java)) }

        section("OWNER SESSION")
        addText("Owner verification remains valid for up to five minutes. The panel is excluded from screenshots. Secrets are never written to the audit log.", 12f, Theme.MUTED, false, 4)
        addButton("Lock Owner Control Centre now", true) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(AUTH_AT).apply()
            finish()
        }
    }

    private fun secretSummary(): String {
        val configured = Providers.all.filter { Prefs.hasKey(this, it.id) }.map { it.label }
        val bybit = Prefs.bybitKey(this).isNotBlank() && Prefs.bybitSecret(this).isNotBlank()
        return buildString {
            append("Provider credentials configured: ")
            append(if (configured.isEmpty()) "none" else configured.joinToString(", "))
            append("\nBybit credentials: ")
            append(if (bybit) "both present (values masked)" else "not fully configured")
            append("\nCredential values are encrypted by the app vault where supported and are not displayed in this summary.")
        }
    }

    private fun showCredentialManager() {
        val providers = Providers.all
        val labels = providers.map { p ->
            p.label + " · " + if (Prefs.hasKey(this, p.id)) "CREDENTIAL STORED" else "not configured"
        }.toMutableList()
        labels += "Bybit exchange · " + if (Prefs.bybitKey(this).isNotBlank() && Prefs.bybitSecret(this).isNotBlank()) "BOTH CREDENTIALS STORED" else "not fully configured"
        AlertDialog.Builder(this).setTitle("Credential vault · values masked")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index < providers.size) {
                    val provider = providers[index]
                    if (Prefs.hasKey(this, provider.id)) {
                        AlertDialog.Builder(this).setTitle(provider.label + " credential")
                            .setMessage("The key is stored in the Android Keystore-backed AES-GCM vault. Its value is intentionally not printed to the screen or audit log.")
                            .setNeutralButton("REMOVE KEY") { _, _ ->
                                if (Prefs.setKey(this, provider.id, "")) {
                                    Toast.makeText(this, provider.label + " credential removed.", Toast.LENGTH_SHORT).show()
                                } else Toast.makeText(this, "Credential removal failed.", Toast.LENGTH_LONG).show()
                            }
                            .setPositiveButton("CLOSE", null).show()
                    } else {
                        Toast.makeText(this, "No stored key for " + provider.label, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AlertDialog.Builder(this).setTitle("Bybit credentials")
                        .setMessage("Remove both stored Bybit credentials? This does not revoke the API key at Bybit; revoke it in your Bybit account as well.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("REMOVE BOTH") { _, _ ->
                            val ok = Prefs.setBybit(this, "", "")
                            Toast.makeText(this, if (ok) "Stored Bybit credentials removed." else "Could not remove stored Bybit credentials.", Toast.LENGTH_LONG).show()
                        }.show()
                }
            }
            .setNegativeButton("CLOSE", null)
            .show()
    }

    private fun showToolRules() {
        val tools = ToolRegistry.all.filter { it.name !in ToolPermissionPolicy.alwaysAllowed }
        fun show() {
            val labels = tools.map { "${it.name} · ${it.access.name.lowercase()} · ${ToolPermissions.mode(this, it.name).name}" }
            AlertDialog.Builder(this).setTitle("App tool permissions")
                .setItems(labels.toTypedArray()) { _, i ->
                    ToolPermissions.cycle(this, tools[i].name)
                    show()
                }
                .setPositiveButton("Done", null).show()
        }
        show()
    }

    private fun showPolicyRegister() {
        val entries = listOf(
            "Tool permissions — configurable Allow / Ask / Deny per registered tool; always-available conversation tools remain reserved.",
            "Autonomy mode — configurable Talk / Assist / Autopilot / Builder; modes do not erase explicit safety gates.",
            "Execution budgets — configurable within enforced app caps: 1–40 tool steps, 1–30 AI requests, 0–4 retries.",
            "Agent approval — configurable Plan / Ask / Allowlist / timed trusted session; trust expires and can be stopped.",
            "Capability packs — individually enabled only when a working adapter exists; unsupported packs remain unavailable.",
            "Task checkpoints — Stop / Pause / recovery confirmation prevent uncertain actions from being silently replayed.",
            "Learning — reusable routes require completed tasks and explicit post-action verification evidence.",
            "Privacy filtering — sensitive values, email addresses, URLs and phone-like numbers are redacted from recovery metadata.",
            "Secrets — provider and exchange credentials are masked in UI and excluded from audit events; manage or rotate them in Settings.",
            "Android permissions — controlled by Android. Orion can report grant state and open system settings, but cannot silently grant itself access.",
            "Accessibility and notification access — user-enabled Android special access; Android can revoke these independently.",
            "Background execution — Android battery, process and foreground-service rules apply; Orion cannot guarantee permanent background execution.",
            "Network/provider limits — provider quotas, billing, model availability and server-side refusals are controlled externally.",
            "OpenAI/system safety instructions — external to this app and not configurable or bypassable from Orion.",
            "Hard security invariants — certain checks cannot be disabled through app preferences; the app must fail closed when required proof or authorization is absent.",
            "Source policy modules — ActionLearningPolicy, AgentDispatchPolicy, AgentPolicy, CheckpointTransitionPolicy, ConversationAutoRoutePolicy, GatePolicy, LinuxRuntimePolicy, OrionKeyboardPolicy, OrionPerformancePolicy, OrionRuntimePolicy, OrionSkillInstallPolicy, ProviderBillingPolicy, ProviderFallbackPolicy, PythonExecutionPolicy, RecoveryPolicy, RoutineReplayCheckpointPolicy, RoutineReplayPolicy, SharedToolSurfacePolicy, ShellPolicy, SkillAuthorizationPolicy, StreamCompletionPolicy, StreamFallbackPolicy, SystemIntentPolicy, TaskScopePolicy, ToolPermissionPolicy and ToolResultPolicy.",
            "Other app policy modules — AgentPrivacyFilter, ChatBrowserUrlPolicy, GeminiLivePolicy, LiveCallStylePolicy, ResearchRoutingPolicy, ResearchFollowUpPolicy, WakeWordPolicy and the MCP scope policy. Their implementation constraints are enforced by their owning modules; this panel does not silently rewrite source code or disable invariants."
        )
        AlertDialog.Builder(this).setTitle("Orion app policy register")
            .setMessage(entries.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n\n"))
            .setPositiveButton("Close", null).show()
    }

    private fun showPermissionStatus() {
        val rows = mutableListOf<String>()
        fun addPermission(label: String, permission: String) {
            val granted = checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
            rows += "$label: " + if (granted) "GRANTED" else "NOT GRANTED"
        }
        addPermission("Microphone", android.Manifest.permission.RECORD_AUDIO)
        addPermission("Contacts", android.Manifest.permission.READ_CONTACTS)
        addPermission("Coarse location", android.Manifest.permission.ACCESS_COARSE_LOCATION)
        addPermission("Fine location", android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) addPermission("Notifications", android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationAccess = runCatching {
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
                .split(':').any { it.equals("$packageName/${OrionNotificationListener::class.java.name}", true) }
        }.getOrDefault(false)
        rows += "Notification listener special access: " + if (notificationAccess) "GRANTED" else "NOT GRANTED"
        val accessibility = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .contains(packageName, true)
        }.getOrDefault(false)
        rows += "Accessibility special access: " + if (accessibility) "GRANTED" else "NOT GRANTED"
        AlertDialog.Builder(this).setTitle("Android permission state")
            .setMessage(rows.joinToString("\n") + "\n\nAndroid owns these grants. Orion cannot override the system's decision.")
            .setPositiveButton("Close", null).show()
    }

    private fun showAudit() {
        val rows = AgentPolicy.recentLogs(this, 40)
        AlertDialog.Builder(this).setTitle("Recent policy audit")
            .setMessage(rows.ifEmpty { listOf("No policy events recorded yet.") }.joinToString("\n\n"))
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                AlertDialog.Builder(this).setTitle("Clear audit history?")
                    .setMessage("This deletes local policy audit events.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        getSharedPreferences("phoneai_agent_policy", Context.MODE_PRIVATE).edit().remove("action_log").apply()
                        Toast.makeText(this, "Local audit history cleared.", Toast.LENGTH_SHORT).show()
                    }.show()
            }.show()
    }

    private fun section(s: String) {
        val view = TextView(this).apply {
            text = s; textSize = 10f; setTextColor(Theme.MUTED)
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = .12f
            setPadding(0, dp(20), 0, dp(5))
        }
        root.addView(view)
    }

    private fun addText(s: String, size: Float, color: Int, bold: Boolean, top: Int) {
        val view = TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(top), 0, dp(2))
        }
        root.addView(view)
    }

    private fun addButton(s: String, primary: Boolean = false, action: () -> Unit) {
        val button = Button(this).apply {
            text = s; isAllCaps = false; setTextColor(Theme.TEXT)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (primary) 0xFF444444.toInt() else 0xFF222222.toInt()
            )
            setOnClickListener { action() }
        }
        root.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
}
