package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Settings / Runtime screen.
 *
 * Provides a unified coding-agent runtime directory with account sign-in,
 * workspace credential mapping, and API key management for Orion PC runtimes:
 * Claude Code, Command Code, Codex, OpenCode, Grok, Cursor Agent, and Muse Code.
 */
class RuntimeAgentsActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private lateinit var root: LinearLayout
    private lateinit var cardsContainer: LinearLayout
    private var activeRuntimeId: String = "opencode"

    private data class RuntimeAgentItem(
        val id: String,
        val name: String,
        val subtitle: String = "Everything in Orizon PC",
        val iconText: String,
        val iconColor: Int,
        val isWorkspaceCredential: Boolean,
        val accountUrl: String,
        val docsUrl: String,
        val providerKeyId: String? = null,
        val note: String
    )

    private val runtimeItems = listOf(
        RuntimeAgentItem(
            id = "claude-code",
            name = "Claude Code",
            subtitle = "Everything in Orizon PC",
            iconText = "✻",
            iconColor = 0xFFD97706.toInt(),
            isWorkspaceCredential = true,
            accountUrl = "https://claude.ai",
            docsUrl = "https://support.claude.com/en/articles/14552382-your-first-day-in-claude-code",
            providerKeyId = "anthropic",
            note = "Uses your workspace credentials or Anthropic API key. Set as ANTHROPIC_API_KEY for each turn."
        ),
        RuntimeAgentItem(
            id = "command-code",
            name = "Command Code",
            subtitle = "Everything in Orizon PC",
            iconText = "⌘",
            iconColor = 0xFFF0F3F6.toInt(),
            isWorkspaceCredential = true,
            accountUrl = "https://github.com/Kingijimzy/phoneai",
            docsUrl = "https://github.com/Kingijimzy/phoneai",
            note = "Uses your workspace credentials and Orion's configured command runtime."
        ),
        RuntimeAgentItem(
            id = "codex",
            name = "Codex",
            subtitle = "Everything in Orizon PC",
            iconText = "◎",
            iconColor = 0xFF10A37F.toInt(),
            isWorkspaceCredential = false,
            accountUrl = "https://chatgpt.com/codex",
            docsUrl = "https://learn.chatgpt.com/docs/cli",
            providerKeyId = "openai",
            note = "Runs on your ChatGPT account or OpenAI API key. Saving a key switches it to the key."
        ),
        RuntimeAgentItem(
            id = "opencode",
            name = "OpenCode",
            subtitle = "Everything in Orizon PC",
            iconText = "🔲",
            iconColor = 0xFF58A6FF.toInt(),
            isWorkspaceCredential = false,
            accountUrl = "https://opencode.ai/auth",
            docsUrl = "https://opencode.ai/docs/providers",
            providerKeyId = "opencode-zen",
            note = "Runs on the account you signed in with in your workspace. Saving a key switches it to the key.\nSet as ANTHROPIC_API_KEY for each turn."
        ),
        RuntimeAgentItem(
            id = "grok",
            name = "Grok",
            subtitle = "Everything in Orizon PC",
            iconText = "⚡",
            iconColor = 0xFFF0F3F6.toInt(),
            isWorkspaceCredential = false,
            accountUrl = "https://grok.com",
            docsUrl = "https://docs.x.ai/",
            providerKeyId = "grok",
            note = "Runs on your Grok account or xAI API key."
        ),
        RuntimeAgentItem(
            id = "cursor-agent",
            name = "Cursor Agent",
            subtitle = "Everything in Orizon PC",
            iconText = "⬡",
            iconColor = 0xFFA371F7.toInt(),
            isWorkspaceCredential = false,
            accountUrl = "https://cursor.com/agents",
            docsUrl = "https://cursor.com/docs/cloud-agent/api/overview",
            providerKeyId = "cursor",
            note = "Runs on your Cursor account or Cursor Cloud Agent API key."
        ),
        RuntimeAgentItem(
            id = "muse-code",
            name = "Muse Code",
            subtitle = "Everything in Orizon PC",
            iconText = "∞",
            iconColor = 0xFF1877F2.toInt(),
            isWorkspaceCredential = false,
            accountUrl = "https://dev.meta.ai",
            docsUrl = "https://dev.meta.ai/docs/muse-code",
            note = "Runs on your Meta account or Muse Code credentials."
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bgColor = 0xFF111315.toInt()
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor

        activeRuntimeId = Prefs.cliRuntime(this)
        if (activeRuntimeId !in runtimeItems.map { it.id }) {
            activeRuntimeId = "opencode"
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(bgColor)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root)
        setContentView(scroll)

        buildHeader()
        buildTabs()

        cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(cardsContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        renderCards()
        buildFooter()
    }

    private fun buildHeader() {
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val title = TextView(this).apply {
            text = "Settings"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF0F3F6.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "Manage model context, notifications, mobile access, and local data"
            textSize = 12f
            setTextColor(0xFF8B949E.toInt())
            setPadding(0, dp(2), 0, 0)
        }
        titleColumn.addView(title)
        titleColumn.addView(subtitle)

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(0xFF8B949E.toInt())
            setPadding(dp(12), dp(6), dp(6), dp(6))
            setOnClickListener { finish() }
        }

        headerLayout.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))
        headerLayout.addView(closeBtn)
        root.addView(headerLayout)
    }

    private fun buildTabs() {
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        tabRow.addView(createTab("General", false) {
            finish()
        })
        tabRow.addView(createTab("⚙  Runtime", true) {})
        tabRow.addView(createTab("Workspace", false) {
            AlertDialog.Builder(this)
                .setTitle("Workspace Settings")
                .setMessage("Active project workspace: /workspace/quiet-darwin\n\nAll coding changes, repository tools, and runtime builds execute within this workspace.")
                .setPositiveButton("OK", null)
                .show()
        })
        tabRow.addView(createTab("Tools", false) {
            startActivity(Intent(this, AgentControlActivity::class.java))
        })

        tabScroll.addView(tabRow)
        root.addView(tabScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }

    private fun createTab(title: String, isActive: Boolean, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = title
            textSize = 13f
            typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(if (isActive) 0xFF58A6FF.toInt() else 0xFF8B949E.toInt())
            background = GradientDrawable().apply {
                setColor(if (isActive) 0xFF1C2433.toInt() else 0xFF1B1D21.toInt())
                cornerRadius = dp(18).toFloat()
                if (isActive) {
                    setStroke(dp(1), 0xFF388BFD.toInt())
                } else {
                    setStroke(dp(1), 0xFF2A2D33.toInt())
                }
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            val lp = LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(8)
            }
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun renderCards() {
        cardsContainer.removeAllViews()
        for (item in runtimeItems) {
            val isSelected = item.id == activeRuntimeId
            cardsContainer.addView(createCard(item, isSelected), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(10)
            })
        }
    }

    private fun createCard(item: RuntimeAgentItem, isSelected: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(if (isSelected) 0xFF181B22.toInt() else 0xFF16181D.toInt())
                cornerRadius = dp(12).toFloat()
                if (isSelected) {
                    setStroke(dp(1), 0xFF388BFD.toInt())
                } else {
                    setStroke(dp(1), 0xFF2A2D33.toInt())
                }
            }
        }

        // Header row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Left icon badge
        val iconBadge = TextView(this).apply {
            text = item.iconText
            textSize = 16f
            setTextColor(item.iconColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF21262D.toInt())
                cornerRadius = dp(8).toFloat()
            }
        }
        headerRow.addView(iconBadge, LinearLayout.LayoutParams(dp(36), dp(36)))

        // Middle title + subtitle
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        val nameView = TextView(this).apply {
            text = item.name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFF0F3F6.toInt())
        }
        val subtitleView = TextView(this).apply {
            text = item.subtitle
            textSize = 11f
            setTextColor(0xFF8B949E.toInt())
            setPadding(0, dp(1), 0, 0)
        }
        titleCol.addView(nameView)
        titleCol.addView(subtitleView)
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        // Right status badge
        val statusText = if (item.isWorkspaceCredential) {
            "uses your workspace credentials"
        } else {
            "signed in with your account"
        }
        val statusColor = if (item.isWorkspaceCredential) 0xFF8B949E.toInt() else 0xFFE5A84B.toInt()

        val statusView = TextView(this).apply {
            text = statusText
            textSize = 11f
            setTextColor(statusColor)
            gravity = Gravity.END
        }
        headerRow.addView(statusView)

        card.addView(headerRow)

        // Tapping card selects it as active runtime
        card.setOnClickListener {
            if (activeRuntimeId != item.id) {
                activeRuntimeId = item.id
                Prefs.setCliRuntime(this, item.id)
                Toast.makeText(this, "Active runtime: ${item.name}", Toast.LENGTH_SHORT).show()
                renderCards()
            }
        }

        // Expanded section for active/selected runtime
        if (isSelected) {
            val expandLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, 0)
            }

            // Row 1: API key input + Save button
            val keyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val currentKey = item.providerKeyId?.let { Prefs.key(this@RuntimeAgentsActivity, it) } ?: ""
            val inputKey = EditText(this).apply {
                hint = "Paste an API key"
                setHintTextColor(0xFF6E7681.toInt())
                setTextColor(0xFFF0F3F6.toInt())
                textSize = 13f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if (currentKey.isNotEmpty()) setText(currentKey)
                background = GradientDrawable().apply {
                    setColor(0xFF13151A.toInt())
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), 0xFF30363D.toInt())
                }
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            keyRow.addView(inputKey, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })

            val saveBtn = Button(this).apply {
                text = "Save"
                textSize = 13f
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(0xFF388BFD.toInt())
                setOnClickListener {
                    val key = inputKey.text.toString().trim()
                    item.providerKeyId?.let { pid ->
                        Prefs.setKey(this@RuntimeAgentsActivity, pid, key)
                    }
                    Prefs.setRuntimeSignedIn(this@RuntimeAgentsActivity, item.id, key.isNotEmpty())
                    Toast.makeText(
                        this@RuntimeAgentsActivity,
                        if (key.isNotEmpty()) "${item.name} API key saved" else "API key cleared",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderCards()
                }
            }
            keyRow.addView(saveBtn, LinearLayout.LayoutParams(-2, dp(40)))
            expandLayout.addView(keyRow)

            // Row 2: Secondary controls (model dropdown or sign in again)
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }

            val orText = TextView(this).apply {
                text = "or"
                textSize = 12f
                setTextColor(0xFF8B949E.toInt())
                setPadding(0, 0, dp(8), 0)
            }
            actionsRow.addView(orText)

            if (item.id == "opencode") {
                val zenProvider = Providers.get("opencode-zen")
                val zenModel = Prefs.model(this, zenProvider, false).ifBlank { "OpenCode Zen (free models)" }
                val modelPickerBtn = Button(this).apply {
                    text = "$zenModel ▾"
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(0xFFF0F3F6.toInt())
                    backgroundTintList = ColorStateList.valueOf(0xFF21262D.toInt())
                    setOnClickListener {
                        val models = arrayOf("OpenCode Zen (free models)", "gpt-6-astra", "claude-3-5-sonnet", "qwen-2.5-coder")
                        AlertDialog.Builder(this@RuntimeAgentsActivity)
                            .setTitle("Choose OpenCode Model")
                            .setItems(models) { _, which ->
                                val choice = models[which]
                                Prefs.setModel(this@RuntimeAgentsActivity, zenProvider, false, choice)
                                text = "$choice ▾"
                                Toast.makeText(this@RuntimeAgentsActivity, "Selected $choice", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                }
                actionsRow.addView(modelPickerBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
            } else {
                val accountBtn = Button(this).apply {
                    text = "Sign in with account"
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(0xFFF0F3F6.toInt())
                    backgroundTintList = ColorStateList.valueOf(0xFF21262D.toInt())
                    setOnClickListener {
                        openUrl(item.accountUrl)
                        Prefs.setRuntimeSignedIn(this@RuntimeAgentsActivity, item.id, true)
                    }
                }
                actionsRow.addView(accountBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(8) })
            }

            val signInAgainBtn = Button(this).apply {
                text = "Sign in again"
                textSize = 12f
                isAllCaps = false
                setTextColor(0xFFF0F3F6.toInt())
                backgroundTintList = ColorStateList.valueOf(0xFF21262D.toInt())
                setOnClickListener {
                    openUrl(item.accountUrl)
                    Prefs.setRuntimeSignedIn(this@RuntimeAgentsActivity, item.id, true)
                }
            }
            actionsRow.addView(signInAgainBtn, LinearLayout.LayoutParams(-2, dp(38)))
            expandLayout.addView(actionsRow)

            // Cursor setup button if cursor
            if (item.id == "cursor-agent") {
                val cursorSetupBtn = Button(this).apply {
                    text = "Configure Cursor Cloud Agent Setup"
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(0xFF58A6FF.toInt())
                    backgroundTintList = ColorStateList.valueOf(0xFF21262D.toInt())
                    setOnClickListener {
                        startActivity(Intent(this@RuntimeAgentsActivity, CursorCloudAgentActivity::class.java))
                    }
                }
                expandLayout.addView(cursorSetupBtn, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(6) })
            }

            // Note text
            val noteView = TextView(this).apply {
                text = item.note
                textSize = 11f
                setTextColor(0xFF8B949E.toInt())
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(8), 0, dp(2))
            }
            expandLayout.addView(noteView)

            card.addView(expandLayout)
        }

        return card
    }

    private fun buildFooter() {
        val footerText = TextView(this).apply {
            text = "New sessions start on this runtime, and each session keeps the one it started on. To move a session to another runtime, use Runtime in the message box's model menu: the conversation comes along. Web search and GitHub work on every runtime; where one lacks something, such as the browser tools, it says so here."
            textSize = 12f
            setTextColor(0xFF8B949E.toInt())
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), dp(16), dp(4), dp(8))
        }
        root.addView(footerText)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            AlertDialog.Builder(this)
                .setMessage("No browser is available to open $url")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
