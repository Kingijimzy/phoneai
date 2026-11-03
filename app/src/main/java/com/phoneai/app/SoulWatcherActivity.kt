package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * A dedicated, local-first control surface for SoulEngineWatcher.
 * Commands here inspect or run the real local watcher; they do not silently grant permissions,
 * change policy, or pretend that a proposal has repaired a capability.
 */
class SoulWatcherActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var transcript: LinearLayout
    private lateinit var commandInput: EditText
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private val ink = Color.rgb(8, 8, 8)
    private val panel = Color.rgb(21, 21, 21)
    private val line = Color.rgb(53, 53, 53)
    private val white = Color.rgb(244, 245, 246)
    private val muted = Color.rgb(165, 169, 174)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        buildUi()
        renderSnapshot()
        addMessage("WATCHER", "I can run the local capability audit, explain detected limitations, list learning proposals, and open the relevant report. I will tell you when a fix requires Android settings or your approval.")
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) renderSnapshot()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val heading = TextView(this).apply {
            text = "SOUL ENGINE / WATCHER"
            setTextColor(white); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(heading)
        root.addView(label("DIRECT CONTROL · LOCAL AUDIT · VERIFIED OUTCOMES", muted, 10f).also { it.setPadding(0, dp(5), 0, dp(14)) })

        val statusCard = card()
        statusView = label("Checking watcher state…", white, 16f).also { it.typeface = Typeface.DEFAULT_BOLD }
        detailView = label("", muted, 12f)
        statusCard.addView(statusView)
        statusCard.addView(detailView.apply { setPadding(0, dp(8), 0, 0) })
        val statusActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusActions.addView(actionButton("Run check now") { runWatcherCheck() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        statusActions.addView(actionButton("Capabilities") { showCapabilities() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        statusCard.addView(statusActions)
        root.addView(statusCard)

        root.addView(label("WATCHER CONVERSATION", white, 13f).also { it.typeface = Typeface.DEFAULT_BOLD; it.setPadding(0, dp(16), 0, dp(8)) })
        val scroll = ScrollView(this).apply { isFillViewport = false }
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(transcript)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        commandInput = EditText(this).apply {
            hint = "Ask: check status, why screen perception unavailable, show proposals…"
            setHintTextColor(muted); setTextColor(white); textSize = 14f
            setSingleLine(false); maxLines = 3; imeOptions = EditorInfo.IME_ACTION_SEND
            setBackgroundColor(panel); setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { submitCommand(); true } else false
            }
        }
        root.addView(commandInput, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(10) })
        root.addView(actionButton("Send to Watcher") { submitCommand() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        setContentView(root)
    }

    private fun renderSnapshot() {
        val snapshot = runCatching { SoulEngineState.snapshot(this) }.getOrNull()
        val core = runCatching { AgentPolicy.enabled(this, "orion_core") }.getOrDefault(false)
        val service = PhoneAiService.instance != null
        val status = snapshot?.watcherStatus?.takeIf { it.isNotBlank() } ?: "not checked yet"
        statusView.text = "Watcher: $status" + if (!core) " · core paused" else ""
        val time = snapshot?.watcherAt?.takeIf { it > 0L }?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "no recorded tick"
        detailView.text = "Last check: $time\nLast action: ${snapshot?.watcherAction?.ifBlank { "none recorded" } ?: "state unavailable"}\nAccessibility service: ${if (service) "connected" else "not connected"}\nPending proposals: ${snapshot?.proposals?.count { it.status == SoulEngineState.ProposalStatus.PENDING } ?: 0}"
    }

    private fun submitCommand() {
        val raw = commandInput.text.toString().trim()
        if (raw.isBlank()) return
        commandInput.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(commandInput.windowToken, 0)
        addMessage("YOU", raw)
        val q = raw.lowercase()
        when {
            q in setOf("check", "check status", "run check", "scan", "audit", "refresh") -> runWatcherCheck()
            q.contains("capabilit") || q.contains("workspace status") || q.contains("what works") || q.contains("what is connected") -> showCapabilities()
            q.contains("proposal") || q.contains("suggestion") -> showProposals()
            q.contains("capability report") || q.contains("capability board") || q.contains("open report") -> openCapabilityReport()
            q.startsWith("why") || q.contains("screen perception") || q.contains("unavailable") -> explainLimitation(raw)
            q == "help" || q == "what can you do" -> addMessage("WATCHER", "Try: “check status”, “show capabilities”, “why screen perception unavailable”, “show proposals”, or “open capability report”. I can inspect and guide repairs, but Android permission changes and consequential actions remain under your control.")
            else -> addMessage("WATCHER", "I don't have a verified local action for that command yet. I haven't changed anything. Try “check status”, “show proposals”, or “why screen perception unavailable”.")
        }
    }

    private fun runWatcherCheck() {
        addMessage("WATCHER", "Running the local capability audit now. This checks current registered tools, enabled packs, accessibility connection and screen-perception readiness.")
        Thread {
            val result = runCatching { SoulEngineWatcher.run(applicationContext) }
            runOnUiThread {
                result.onSuccess { r ->
                    renderSnapshot()
                    val limits = if (r.limitations.isEmpty()) "No capability limitations were reported by this audit." else "Findings: " + r.limitations.joinToString(", ")
                    addMessage("WATCHER", "Audit finished. Status: ${r.status}. Action: ${r.action}. $limits\nNext step: ${r.nextStep}\nThis is a fresh audit result, not proof that every feature works.")
                    showProposals()
                }.onFailure { e ->
                    addMessage("WATCHER", "The local audit failed: ${e.message ?: e.javaClass.simpleName}. No repair is claimed. You can retry or open the Capability Report.")
                }
            }
        }.start()
    }

    private fun showCapabilities() {
        addMessage("WATCHER", "Reading the shared live capability registry, enabled packs, Android permission state and saved integration metadata. A saved connection is not proof of a successful live request.")
        Thread {
            val snapshot = runCatching { OrionCapabilityHub.snapshot(applicationContext) }
            runOnUiThread {
                snapshot.onSuccess { s ->
                    addMessage("WATCHER", "Capability snapshot at ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.checkedAt))}. Registered tools: ${s.registeredTools}; available under current policy: ${s.availableTools}.")
                    s.capabilities.forEach { capability ->
                        val box = card(transcript)
                        box.addView(label(capability.name, white, 14f).also { it.typeface = Typeface.DEFAULT_BOLD })
                        box.addView(label(OrionCapabilityHub.stateLabel(capability.state), when (capability.state) {
                            OrionCapabilityHub.State.READY -> Color.rgb(145, 205, 151)
                            OrionCapabilityHub.State.LIMITED -> Color.rgb(255, 194, 120)
                            OrionCapabilityHub.State.DISABLED, OrionCapabilityHub.State.SETUP_REQUIRED -> Color.rgb(255, 155, 135)
                            OrionCapabilityHub.State.UNKNOWN -> muted
                        }, 10f))
                        box.addView(label(capability.detail, muted, 12f).apply { setPadding(0, dp(5), 0, 0) })
                        box.addView(label("Tools: " + capability.tools.take(8).joinToString(", ") + if (capability.tools.size > 8) "…" else "", muted, 10f).apply { setPadding(0, dp(5), 0, 0) })
                    }
                    if (s.integrationStates.isNotEmpty()) {
                        addMessage("WATCHER", "Integration metadata (not live end-to-end proof): " + s.integrationStates.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    }
                    if (s.limitations.isNotEmpty()) addMessage("WATCHER", "Current blockers: " + s.limitations.take(12).joinToString(" | "))
                    else addMessage("WATCHER", "No blockers were reported by this capability snapshot. This does not mean every tool has passed a live end-to-end test.")
                }.onFailure { e ->
                    addMessage("WATCHER", "Capability snapshot failed: ${e.message ?: e.javaClass.simpleName}. No readiness claim was made.")
                }
            }
        }.start()
    }

    private fun explainLimitation(raw: String) {
        val snapshot = runCatching { SoulEngineState.snapshot(this) }.getOrNull()
        val all = listOfNotNull(snapshot?.watcherAction?.takeIf { it.contains("screen", true) }, snapshot?.lastOutcome?.takeIf { it.contains("screen", true) })
        val screenIssue = raw.contains("screen", true) || all.any { it.contains("screen_perception", true) }
        if (screenIssue) {
            addMessage("WATCHER", "screen_perception_unavailable means Orion could not verify a usable active-window accessibility root during this check. Check that PhoneAI's Accessibility service is enabled, the screen-access disclosure was accepted, and an app window is currently visible. Android/OEM restrictions can still make the root unavailable. I cannot enable the accessibility service silently. Open the Capability Report, correct the setup if needed, then run the audit again.")
        } else {
            addMessage("WATCHER", "I need a fresh local audit to report current limitations accurately. Say “check status” and I will run it; this explanation command itself does not change settings or run background tasks.")
        }
    }

    private fun showProposals() {
        val pending = runCatching { SoulEngineState.snapshot(this).proposals.filter { it.status == SoulEngineState.ProposalStatus.PENDING } }.getOrDefault(emptyList())
        if (pending.isEmpty()) {
            addMessage("WATCHER", "There are no pending proposals right now.")
            return
        }
        addMessage("WATCHER", "Pending proposals (${pending.size}). Accepting records your decision only; it does not perform the repair.")
        pending.take(8).forEach { p ->
            val box = card(transcript)
            box.addView(label(p.title, white, 14f).also { it.typeface = Typeface.DEFAULT_BOLD })
            box.addView(label(p.detail, muted, 12f).apply { setPadding(0, dp(6), 0, dp(8)) })
            box.addView(label("Evidence: ${p.evidenceCount} · Type: ${p.kind.name.lowercase()}", muted, 10f))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(actionButton("Accept") { review(p, SoulEngineState.ProposalStatus.ACCEPTED) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
            row.addView(actionButton("Reject") { review(p, SoulEngineState.ProposalStatus.REJECTED) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
            box.addView(row)
            transcript.addView(box)
        }
    }

    private fun review(p: SoulEngineState.Proposal, status: SoulEngineState.ProposalStatus) {
        val ok = runCatching { SoulEngineState.reviewProposal(this, p.id, status, null) }.getOrDefault(false)
        addMessage("WATCHER", if (ok) "Proposal ${status.name.lowercase()} recorded. No permission, source, or tool behavior was changed." else "I couldn't save that decision. Refresh the proposals and try again.")
        renderSnapshot()
    }

    private fun openCapabilityReport() {
        runCatching { startActivity(Intent(this, OrionCapabilityReportActivity::class.java)) }
            .onFailure { Toast.makeText(this, "Capability report could not be opened: ${it.message}", Toast.LENGTH_LONG).show() }
    }

    private fun addMessage(speaker: String, message: String) {
        if (!::transcript.isInitialized) return
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            setBackgroundColor(if (speaker == "YOU") Color.rgb(31, 37, 43) else panel)
        }
        bubble.addView(label(speaker, if (speaker == "YOU") Color.rgb(164, 199, 230) else white, 10f).also { it.typeface = Typeface.DEFAULT_BOLD })
        bubble.addView(label(message, if (speaker == "YOU") white else muted, 13f).apply { setPadding(0, dp(5), 0, 0) })
        val params = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        transcript.addView(bubble, params)
    }

    private fun card(parent: LinearLayout = root) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setBackgroundColor(panel)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(panel); cornerRadius = dp(18).toFloat(); setStroke(dp(1), line)
        }
        val p = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        parent.addView(this, p)
    }

    private fun label(value: String, color: Int, size: Float) = TextView(this).apply {
        text = value; setTextColor(color); textSize = size
    }

    private fun actionButton(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; setTextColor(white); setBackgroundColor(Color.rgb(42, 46, 51))
        setOnClickListener { click() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
