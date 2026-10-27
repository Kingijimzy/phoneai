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

        val integrations = card()
        integrations.addView(label("INTEGRATIONS", 10f, 0xFFAAAAAA.toInt(), true))
        addLine(integrations, "AI provider keys", providerKeys.toString() + " of " + Providers.all.size + " providers have a saved key. This does not validate the key, model, credits, quota, or network.", providerKeys > 0)
        addLine(integrations, "Service credentials", savedServices.toString() + " credential(s) saved in the local connection store. Saved is not the same as provider-verified.", null)
        addLine(integrations, "Speech recognition", if (speechAvailable) "Android reports a recognizer is available; microphone capture and transcription still need a real test." else "Android reports no speech recognizer available.", speechAvailable)
        page.addView(integrations)

        val limits = card()
        limits.addView(label("WHAT NEEDS ATTENTION", 10f, 0xFFAAAAAA.toInt(), true))
        val messages = report.limitations.mapNotNull { limitationText(it) }.toMutableList()
        if (providerKeys == 0) messages += "No AI provider key is saved; online model responses may be unavailable."
        if (savedServices == 0) messages += "No external-service credentials are saved."
        if (!speechAvailable) messages += "Install or enable a speech-recognition service before testing voice input."
        if (messages.isEmpty()) messages += "No core blocker was detected. Run real chat, voice, screen-action, and provider tests before treating the app as fully verified."
        messages.distinct().forEach { limits.addView(label("• " + it, 12f, 0xFFE0E0E0.toInt())) }
        page.addView(limits)

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
