package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Account-based coding-agent directory.
 *
 * Opening a vendor website is not treated as successful authentication. An agent is
 * marked runnable only when a supported local/remote runtime confirms its own login.
 * This screen deliberately does not scrape cookies or reuse credentials from browsers.
 */
class RuntimeAgentsActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private lateinit var root: LinearLayout
    private lateinit var stateLabels: MutableMap<String, TextView>

    private data class RuntimeAgent(
        val id: String,
        val name: String,
        val subtitle: String,
        val auth: String,
        val accountUrl: String,
        val docsUrl: String,
        val runtimeNote: String
    )

    private val agents = listOf(
        RuntimeAgent(
            "codex", "Codex", "ChatGPT account · coding agent",
            "ChatGPT account sign-in or API key",
            "https://chatgpt.com/codex", "https://learn.chatgpt.com/docs/cli",
            "Needs the Codex CLI/runtime to complete OAuth and execute coding tasks. Opening ChatGPT does not connect a runtime."
        ),
        RuntimeAgent(
            "claude", "Claude Code", "Claude account · coding agent",
            "Eligible Claude plan login, Console API key, or configured cloud provider",
            "https://claude.ai", "https://support.claude.com/en/articles/14552382-your-first-day-in-claude-code",
            "Needs Claude Code installed in a supported runtime. Browser sign-in alone does not install or attach the CLI."
        ),
        RuntimeAgent(
            "opencode", "OpenCode", "Open-source coding agent",
            "Provider-specific sign-in; Zen requires its own API key",
            "https://opencode.ai/auth", "https://opencode.ai/docs/providers",
            "OpenCode CLI/runtime must be installed and authenticated. Zen website login alone does not authorize model requests."
        ),
        RuntimeAgent(
            "cursor", "Cursor Cloud Agent", "Remote repository coding",
            "Cursor Cloud Agent API key",
            "https://cursor.com/agents", "https://cursor.com/docs/cloud-agent/api/overview",
            "This is a cloud-agent API integration, not a browser OAuth session. Configure its key in the dedicated Cursor screen."
        ),
        RuntimeAgent(
            "grok", "Grok", "xAI models / coding tools",
            "Provider API key or a separately supported runtime login",
            "https://grok.com", "https://docs.x.ai/",
            "Opening Grok does not connect an xAI API key or CLI session to Orion."
        ),
        RuntimeAgent(
            "command", "Command Code", "Use the configured command runtime",
            "Uses Orion's Command provider unless a real runtime adapter is added",
            "https://github.com/Kingijimzy/phoneai", "https://github.com/Kingijimzy/phoneai",
            "Not a separate account provider yet. Orion must not display this as signed in until an adapter reports a real session."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        stateLabels = mutableMapOf()
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Theme.BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(root)
        setContentView(scroll)

        addText("ORION / RUNTIME", 10f, Theme.MUTED, true, 0)
        addText("Agent accounts", 27f, Theme.TEXT, true, 4)
        addText(
            "Choose a coding agent and open its official sign-in or setup. Account authentication, API-provider keys, and a working execution runtime are separate states. Orion will never claim a login succeeded just because a website opened.",
            13f, Theme.MUTED, false, 8
        )
        addButton("‹ Back to Agent Control") { finish() }

        section("AGENT RUNTIMES")
        agents.forEach { agent -> addAgent(agent) }

        section("IMPORTANT")
        addText(
            "A cloud workspace can run a provider's CLI and keep its login session on that workspace. This Android app does not yet provide a general cloud PC runtime for every agent. Codex/Claude Code account login must be completed by their actual CLI/runtime; OpenCode Zen uses its own API key. Capacity errors belong to the runtime service and should be shown as errors, not successful sign-ins.",
            12f, Theme.MUTED, false, 5
        )
        addButton("OPEN TERMUX RUNTIME SETUP") {
            startActivity(Intent(this, TermuxBridgeActivity::class.java))
        }
        addButton("OPEN API PROVIDER SETTINGS") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun addAgent(agent: RuntimeAgent) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = GradientDrawable().apply {
                setColor(0xFF171717.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF343434.toInt())
            }
        }
        card.addView(TextView(this).apply {
            text = agent.name
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Theme.TEXT)
        })
        card.addView(TextView(this).apply {
            text = agent.subtitle
            textSize = 12f
            setTextColor(Theme.MUTED)
            setPadding(0, dp(2), 0, dp(5))
        })
        val state = TextView(this).apply {
            text = "NOT CONNECTED · runtime session not verified"
            textSize = 11f
            setTextColor(0xFFFFC27A.toInt())
        }
        stateLabels[agent.id] = state
        card.addView(state)
        card.addView(TextView(this).apply {
            text = "Sign-in method: ${agent.auth}\n${agent.runtimeNote}"
            textSize = 12f
            setTextColor(Theme.MUTED)
            setPadding(0, dp(6), 0, dp(4))
        })
        card.addView(actionButton("OPEN OFFICIAL SIGN-IN / ACCOUNT") {
            openUrl(agent.accountUrl)
            state.text = "ACCOUNT PAGE OPENED · runtime login not yet verified"
        })
        card.addView(actionButton("OPEN OFFICIAL RUNTIME / AUTH DOCS") {
            openUrl(agent.docsUrl)
        })
        if (agent.id == "cursor") {
            card.addView(actionButton("OPEN CURSOR CLOUD AGENT SETUP") {
                startActivity(Intent(this, CursorCloudAgentActivity::class.java))
            })
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun addText(text: String, size: Float, color: Int, bold: Boolean, top: Int) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    private fun section(text: String) {
        addText(text, 10f, Theme.MUTED, true, 18)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Theme.TEXT)
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF252525.toInt())
        setOnClickListener { action() }
    }

    private fun addButton(label: String, action: () -> Unit) {
        root.addView(actionButton(label, action), LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            android.app.AlertDialog.Builder(this)
                .setMessage("No browser is available to open this official page.")
                .setPositiveButton("OK", null).show()
        }
    }
}
