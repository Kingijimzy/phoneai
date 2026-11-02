package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Honest local readiness snapshot; this is not a benchmark or a promise every action works. */
class OrionCapabilityReportActivity : Activity() {
    private lateinit var page: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(3))
        }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(0xFF171717.toInt()); cornerRadius = dp(15).toFloat()
            setStroke(dp(1), 0xFF353535.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun addLine(parent: LinearLayout, title: String, detail: String, good: Boolean? = null) {
        parent.addView(label(title, 14f, 0xFFF0F0F0.toInt(), true))
        parent.addView(label(detail, 12f, when (good) {
            true -> 0xFF9ED6A2.toInt()
            false -> 0xFFFFC078.toInt()
            null -> 0xFFAAAAAA.toInt()
        }))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090909.toInt(); window.navigationBarColor = 0xFF090909.toInt()
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF090909.toInt()) }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(30))
        }
        scroll.addView(page); setContentView(scroll); render()
    }
    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) render()
    }
    private fun render() {
        page.removeAllViews()
        page.addView(label("ORION / DIAGNOSTICS", 10f, 0xFFAAAAAA.toInt(), true))
        page.addView(label("Capability report", 27f, 0xFFF3F3F3.toInt(), true))
        page.addView(label("A local snapshot of what is configured and available. Orion does not mark a feature successful just because its screen or code exists.", 13f, 0xFFAAAAAA.toInt()))

        val service = PhoneAiService.instance
        val accessibilityReady = service != null && runCatching { service.serviceInfo != null }.getOrDefault(false)
        val disclosureAccepted = Prefs.accessibilityDisclosureAccepted(this)
        val windowReady = service != null && runCatching {
            val root = service.rootInActiveWindow
            val ready = root != null
            root?.recycle()
            ready
        }.getOrDefault(false)
        val registeredTools = runCatching { ToolRegistry.all.size }.getOrDefault(0)
        val readTools = runCatching { ToolRegistry.all.count { it.access == ToolAccess.READ } }.getOrDefault(0)
        val writeTools = registeredTools - readTools
        val implementedPacks = AgentPolicy.packs.count { it.implemented }
        val enabledPacks = AgentPolicy.packs.count { it.implemented && AgentPolicy.enabled(this, it.id) }
        val report = OrionCapabilityAudit.evaluate(
            registeredTools, implementedPacks, enabledPacks, accessibilityReady,
            disclosureAccepted, accessibilityReady && windowReady
        )
        val providerKeys = Providers.all.count { Prefs.hasKey(this, it.id) }
        val savedServices = runCatching { OrionConnectionStore.list(this).count { it.secretAvailable } }.getOrDefault(0)
        val speechAvailable = android.speech.SpeechRecognizer.isRecognitionAvailable(this)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val hero = card()
        val status = when (report.status) {
            OrionCapabilityAudit.Status.READY -> "READY — core prerequisites present"
            OrionCapabilityAudit.Status.LIMITED -> "LIMITED — some prerequisites missing"
            OrionCapabilityAudit.Status.BLOCKED -> "BLOCKED — core capability unavailable"
        }
        val statusColor = when (report.status) {
            OrionCapabilityAudit.Status.READY -> 0xFF9ED6A2.toInt()
            OrionCapabilityAudit.Status.LIMITED -> 0xFFFFC078.toInt()
            OrionCapabilityAudit.Status.BLOCKED -> 0xFFFF8585.toInt()
        }
        hero.addView(label(status, 16f, statusColor, true))
        hero.addView(label("Checked locally at " + now, 11f, 0xFF999999.toInt()))
        hero.addView(label("This is not a success-rate score. READY does not prove every provider, voice engine, screen, or external action works.", 12f, 0xFFCCCCCC.toInt()))
        page.addView(hero)

        val core = card()
        core.addView(label("CORE READINESS", 10f, 0xFFAAAAAA.toInt(), true))
        addLine(core, "Agent service", if (accessibilityReady) "Accessibility service is connected." else "Not connected. Phone-control and service-driven features are unavailable.", accessibilityReady)
        addLine(core, "Screen access consent", if (disclosureAccepted) "Disclosure accepted in Orion settings." else "Not accepted. Orion must not assume permission to inspect the screen.", disclosureAccepted)
        addLine(core, "Current screen", if (windowReady) "An active accessibility window is readable right now." else "No active accessibility window was readable during this check. Some apps may still block access.", windowReady)
        addLine(core, "Registered tools", registeredTools.toString() + " registered · " + readTools + " read-only · " + writeTools + " non-read-only. Registration does not guarantee success.", registeredTools > 0)
        addLine(core, "Capability packs", implementedPacks.toString() + " implemented in source · " + enabledPacks + " enabled in settings. Disabled packs are not automatically enabled.", enabledPacks > 0)
        page.addView(core)

        // Show the real routing configuration and transcript availability, without implying
        // that a saved provider key or selected model has passed a live network test.
        val contextCard = card()
        contextCard.addView(label("CONVERSATION & ROUTING", 10f, 0xFFAAAAAA.toInt(), true))
        val executionMode = runCatching { Prefs.executionMode(this) }.getOrDefault("UNKNOWN")
        val modeDescription = when (executionMode) {
            "PLAN" -> "Plan mode: prepare and reason about actions; execution is restricted by mode policy."
            "ACT" -> "Act mode: execute supported actions through Orion's normal permission and safety gates."
            "FULL" -> "Full mode: broader action policy may apply, but approvals and safety gates still apply."
            else -> "Mode could not be read."
        }
        addLine(contextCard, "Execution mode", "${executionMode} — ${modeDescription}", executionMode != "UNKNOWN")
        val selectedProvider = runCatching { Prefs.provider(this, false) }.getOrNull()
        val selectedModel = selectedProvider?.let { runCatching { Prefs.model(this, it, false) }.getOrNull() }.orEmpty()
        addLine(
            contextCard,
            "Chat provider / model",
            if (selectedProvider == null) "Could not read selected provider." else
                "${selectedProvider.label} · ${selectedModel.ifBlank { selectedProvider.textModel }}. Selection is not proof of live connectivity or remaining quota.",
            selectedProvider != null
        )
        val commandProvider = runCatching { Prefs.commandProviderId(this)?.let { Providers.find(it) } ?: Prefs.defaultCommandProvider(this) }.getOrNull()
        val commandModel = runCatching { Prefs.commandModelId(this) }.getOrNull()
        addLine(
            contextCard,
            "Command planner",
            if (commandProvider == null) "Could not resolve the command provider." else
                "${commandProvider.label} · ${commandModel?.takeIf { it.isNotBlank() } ?: commandProvider.textModel}. Command mode may share or override the chat provider.",
            commandProvider != null
        )
        val transcriptCount = runCatching { service?.conversationContext()?.size }.getOrNull()
        addLine(
            contextCard,
            "Saved conversation context",
            transcriptCount?.let { "${it} transcript row(s) available to restore. The active request still uses a bounded recent context; this does not prove every follow-up is interpreted correctly." }
                ?: "Conversation storage could not be checked while the service is unavailable.",
            transcriptCount != null
        )
        addLine(
            contextCard,
            "Live tool verification",
            "This screen does not yet run an end-to-end test for every tool. Registered tools, enabled packs, selected models and saved credentials are not proof of successful execution.",
            false
        )
        page.addView(contextCard)

        val integrations = card()
        integrations.addView(label("INTEGRATIONS", 10f, 0xFFAAAAAA.toInt(), true))
        addLine(integrations, "AI provider keys", providerKeys.toString() + " of " + Providers.all.size + " providers have a saved key. This does not validate the key, model, credits, quota, or network.", providerKeys > 0)
        addLine(integrations, "Service credentials", savedServices.toString() + " credential(s) saved in the local connection store. Saved is not the same as provider-verified.", null)
        addLine(integrations, "Speech recognition", if (speechAvailable) "Android reports a recognizer is available; microphone capture and transcription still need a real test." else "Android reports no speech recognizer available.", speechAvailable)
        page.addView(integrations)

        // Per-tool inventory: eligibility is derived from the registry, enabled packs and known
        // prerequisites. It is deliberately labelled as NOT a live backend health test.
        val enabledPackIds = AgentPolicy.packs
            .filter { it.implemented && AgentPolicy.enabled(this, it.id) }
            .map { it.id }.toSet()
        val contactsState = runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.READ_CONTACTS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        }.getOrDefault(OrionCapabilityDiscovery.PermissionState.UNKNOWN)
        val notificationState = runCatching {
            if (android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                    ?.contains(packageName) == true
            ) OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        }.getOrDefault(OrionCapabilityDiscovery.PermissionState.UNKNOWN)
        val accessibilityState = if (disclosureAccepted && accessibilityReady)
            OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        val locationState = runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        }.getOrDefault(OrionCapabilityDiscovery.PermissionState.UNKNOWN)
        val cameraState = runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.CAMERA
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        }.getOrDefault(OrionCapabilityDiscovery.PermissionState.UNKNOWN)
        val termuxReady = runCatching { TermuxBridgeRunner.readiness(this) == null }.getOrDefault(false)
        val mcpEndpointState = runCatching { if (McpConnector.configured(this)) "configured" else "not-configured" }.getOrDefault("unknown")
        val mcpPairingState = runCatching { if (McpConnector.isPaired(this)) "paired" else "not-paired" }.getOrDefault("unknown")
        val inventory = runCatching {
            OrionCapabilityDiscovery.discover(
                ToolRegistry.all,
                enabledPackIds,
                termuxReady,
                permissionStates = mapOf(
                    "contacts" to contactsState,
                    "notification_access" to notificationState,
                    "accessibility" to accessibilityState,
                    "location" to locationState,
                    "camera" to cameraState
                ),
                integrationStates = mapOf(
                    "mcp_endpoint" to mcpEndpointState,
                    "mcp_pairing" to mcpPairingState,
                    "mcp_live_discovery" to "not-run-by-capability-report"
                )
            )
        }.getOrNull()
        val toolsCard = card()
        toolsCard.addView(label("TOOL-BY-TOOL AVAILABILITY", 10f, 0xFFAAAAAA.toInt(), true))
        toolsCard.addView(label(
            "Eligible means registered, pack enabled and known prerequisites satisfied. It does NOT mean the backend has been live-tested or that an action will succeed.",
            12f, 0xFFAAAAAA.toInt()
        ))
        if (inventory == null) {
            toolsCard.addView(label("Tool availability could not be calculated.", 12f, 0xFFFFC078.toInt()))
        } else {
            toolsCard.addView(label(
                "${inventory.availableCount} of ${inventory.registeredCount} tools eligible to attempt · ${inventory.registeredCount - inventory.availableCount} blocked",
                13f, 0xFFF0F0F0.toInt(), true
            ))
            inventory.tools.sortedWith(compareBy<OrionCapabilityDiscovery.ToolFact> { !it.available }.thenBy { it.name })
                .forEach { tool ->
                    addLine(
                        toolsCard,
                        "${if (tool.available) "ELIGIBLE" else "BLOCKED"} · ${tool.name} (${tool.access.name.lowercase()})",
                        if (tool.available) "Prerequisites look satisfied; execution not live-verified." else tool.reason,
                        if (tool.available) null else false
                    )
                }
            if (inventory.blockers.isNotEmpty()) {
                toolsCard.addView(label("Runtime notes: " + inventory.blockers.joinToString("; "), 11f, 0xFFFFC078.toInt()))
            }
        }
        page.addView(toolsCard)

        // The same grouped snapshot is shown in the Watcher and here so both destinations agree.
        val shared = runCatching { OrionCapabilityHub.snapshot(this, service) }.getOrNull()
        if (shared != null) {
            val sharedCard = card()
            sharedCard.addView(label("CONNECTED CAPABILITY HUB", 10f, 0xFFAAAAAA.toInt(), true))
            sharedCard.addView(label("${shared.registeredTools} registered tools · ${shared.availableTools} eligible under current policy", 12f, 0xFFCCCCCC.toInt()))
            shared.capabilities.forEach { capability ->
                val good = capability.state == OrionCapabilityHub.State.READY
                val color = when (capability.state) {
                    OrionCapabilityHub.State.READY -> 0xFF9ED6A2.toInt()
                    OrionCapabilityHub.State.LIMITED -> 0xFFFFC078.toInt()
                    OrionCapabilityHub.State.DISABLED, OrionCapabilityHub.State.SETUP_REQUIRED -> 0xFFFF8585.toInt()
                    OrionCapabilityHub.State.UNKNOWN -> 0xFFAAAAAA.toInt()
                }
                sharedCard.addView(label("${OrionCapabilityHub.stateLabel(capability.state)} · ${capability.name}", 13f, color, true))
                sharedCard.addView(label(capability.detail, 11f, 0xFFBBBBBB.toInt()))
            }
            if (shared.integrationStates.isNotEmpty()) {
                sharedCard.addView(label("Connection metadata only: " + shared.integrationStates.entries.joinToString("; ") { "${it.key}=${it.value}" }, 10f, 0xFFAAAAAA.toInt()))
            }
            page.addView(sharedCard)
        }

        val limits = card()
        limits.addView(label("WHAT NEEDS ATTENTION", 10f, 0xFFAAAAAA.toInt(), true))
        val messages = report.limitations.mapNotNull { limitationText(it) }.toMutableList()
        if (providerKeys == 0) messages += "No AI provider key is saved; online model responses may be unavailable."
        if (savedServices == 0) messages += "No external-service credentials are saved."
        if (!speechAvailable) messages += "Install or enable a speech-recognition service before testing voice input."
        if (messages.isEmpty()) messages += "No core blocker was detected. Run real chat, voice, screen-action, and provider tests before treating the app as fully verified."
        messages.distinct().forEach { limits.addView(label("• " + it, 12f, 0xFFE0E0E0.toInt())) }
        page.addView(limits)

        val activityCard = card()
        activityCard.addView(label("RECENT TOOL ACTIVITY", 10f, 0xFFAAAAAA.toInt(), true))
        activityCard.addView(label("Local audit history records tool names and generic outcomes only; it does not store tool arguments or prove the user's goal was achieved.", 12f, 0xFFAAAAAA.toInt()))
        val recentEvents = runCatching { ActionLogStore.all(this).take(8) }.getOrDefault(emptyList())
        if (recentEvents.isEmpty()) {
            activityCard.addView(label("No tool attempts are recorded yet.", 12f, 0xFFAAAAAA.toInt()))
        } else {
            recentEvents.forEach { event ->
                val stamp = runCatching {
                    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.at))
                }.getOrDefault("time unknown")
                val isError = event.outcome.equals("error returned", ignoreCase = true)
                val status = if (isError) "ERROR RETURNED" else "RESULT RETURNED · NOT PROOF OF SUCCESS"
                val detail = if (event.failureCategory.isBlank()) status else "$status · ${event.failureCategory}"
                addLine(activityCard, "${event.tool} · $stamp", detail, if (isError) false else null)
            }
        }
        page.addView(activityCard)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(Button(this).apply {
            text = "Refresh report"; isAllCaps = false; setOnClickListener { render() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        actions.addView(Button(this).apply {
            text = "Capability board"; isAllCaps = false
            setOnClickListener { startActivity(Intent(this@OrionCapabilityReportActivity, CapabilityBoardActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        page.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }
    private fun limitationText(key: String): String? = when (key) {
        "no_registered_tools" -> "No tools are registered with the agent."
        "no_implemented_capability_packs" -> "No implemented capability packs were found."
        "no_enabled_capability_packs" -> "All implemented capability packs are disabled. Enable only packs you intend to use."
        "accessibility_service_unavailable" -> "Orion's accessibility service is not connected. Phone-control and screen-aware behavior cannot run."
        "screen_access_disclosure_not_accepted" -> "Screen-access disclosure has not been accepted."
        "screen_perception_unavailable" -> "The current screen could not be read during this snapshot."
        else -> null
    }
}
