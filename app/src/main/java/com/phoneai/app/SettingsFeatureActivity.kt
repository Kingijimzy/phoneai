package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Graphite settings hub. Each domain opens its dedicated owner screen or Android's
 * own permission surface; this activity does not proxy or duplicate settings state.
 */
class SettingsFeatureActivity : Activity() {
    companion object {
        const val EXTRA_SECTION = "settings_feature"
        private val VALID = setOf("all", "providers", "voice", "character", "permissions", "recording")
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }
    private fun surface() = GradientDrawable().apply {
        setColor(Theme.PANEL); cornerRadius = dp(Theme.CORNER_MEDIUM_DP).toFloat()
        setStroke(dp(1), Theme.LINE)
    }
    private fun card(title: String, detail: String, actionLabel: String, action: () -> Unit) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15))
            background = surface()
        }
        block.addView(label(title, 16f, Theme.TEXT, true))
        block.addView(label(detail, 12f, Theme.MUTED).apply { setPadding(0, dp(5), 0, dp(11)) })
        val button = TextView(this).apply {
            text = "$actionLabel  ›"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Theme.TEXT); typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(48); contentDescription = "$actionLabel: $title"
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = GradientDrawable().apply {
                setColor(Theme.PANEL2); cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
                setStroke(dp(1), Theme.LINE_STRONG)
            }
            isClickable = true; isFocusable = true
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> view.animate()
                        .scaleX(0.985f).scaleY(0.985f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate()
                        .scaleX(1f).scaleY(1f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
                }
                false
            }
            setOnClickListener { runCatching(action).onFailure {
                Toast.makeText(this@SettingsFeatureActivity, "Couldn't open this feature: ${it.message ?: "please try again"}", Toast.LENGTH_LONG).show()
            } }
        }
        block.addView(button)
        block.alpha = 0f
        block.translationY = dp(6).toFloat()
        block.post {
            block.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(Theme.MOTION_MEDIUM_MS.toLong())
                .start()
        }
        root.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }
    private lateinit var root: LinearLayout
    private var section = "providers"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = (intent.getStringExtra(EXTRA_SECTION) ?: "providers").lowercase()
        if (section !in VALID) section = "providers"
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(22), dp(17), dp(30))
        }
        scroll.addView(root); setContentView(scroll)
        render()
    }
    private fun render() {
        val heading = when(section) {
            "all" -> Triple("Settings", "A clean control room for Orion identity, providers, voice, permissions and device access.", "SETTINGS OVERVIEW")
            "providers" -> Triple("Providers & models", "Choose the models and connections Orion uses. Keys stay in the encrypted capability vault.", "AI CONNECTIONS")
            "voice" -> Triple("Voice & speech", "Configure speech input, microphone access, spoken responses and live conversation.", "VOICE EXPERIENCE")
            "character" -> Triple("Character & response style", "Review Orion's fixed identity, entity philosophy and approved languages.", "ASSISTANT IDENTITY")
            "permissions" -> Triple("Permissions & device access", "Review Android permissions and system access. Android owns these switches; Orion cannot silently grant them.", "DEVICE ACCESS")
            else -> Triple("Screen recording", "Start an Android-approved capture session and review recording access.", "CAPTURE")
        }
        root.addView(TextView(this).apply {
            text = "‹   Back"; textSize = 14f; setTextColor(Theme.TEXT_SECONDARY)
            setPadding(0, dp(3), 0, dp(12)); minHeight = dp(48)
            contentDescription = "Back to settings"; isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })
        root.addView(label("ORION  /  SETTINGS", 10f, Theme.MUTED, true))
        root.addView(label(heading.first, 27f, Theme.TEXT, true).apply { setPadding(0, dp(7), 0, dp(5)) })
        root.addView(label(heading.second, 13f, Theme.MUTED).apply { setPadding(0, 0, 0, dp(12)) })
        root.addView(label(heading.third, 10f, Theme.SILVER, true).apply { letterSpacing = .12f })
        when(section) {
            "all" -> {
                card("AI providers and models", "Choose chat and vision models, manage encrypted API keys, configure local Ollama and control provider fallback.", "Open model settings") {
                    startActivity(Intent(this, ModelSettingsActivity::class.java))
                }
                card("Codex and Antigravity accounts", "Connect or verify your official account sessions and expose their real model tools to Orion.", "Manage account connections") {
                    startActivity(Intent(this, AccountConnectionsActivity::class.java))
                }
                card("Voice and speech", "Configure spoken replies, offline speech, wake word, neural voice and Gemini Live.", "Open voice settings") {
                    startActivity(Intent(this, VoiceSettingsActivity::class.java))
                }
                card("Orion identity and philosophy", "Review Orion's consistent identity, entity principles and approved languages.", "Open identity settings") {
                    startActivity(Intent(this, CharacterSettingsActivity::class.java))
                }
                card("Permissions and device access", "Review Android permissions, accessibility, notification access and the private keyboard.", "Review permissions") {
                    startActivity(Intent(this, SettingsFeatureActivity::class.java).putExtra(EXTRA_SECTION, "permissions"))
                }
                card("Capabilities and integrations", "Review connected services, tool permissions, credentials and connection status.", "Open capability board") {
                    startActivity(Intent(this, CapabilityBoardActivity::class.java))
                }
                card("Telegram bot", "Connect your private Telegram bot, verify the owner chat, send a test reply and control the background listener.", "Configure Telegram") {
                    startActivity(Intent(this, TelegramSettingsActivity::class.java))
                }
                card("Remote MCP", "Configure trusted MCP endpoints, local pairing, least-privilege scopes and remote tool discovery.", "Open MCP settings") {
                    startActivity(Intent(this, McpSettingsActivity::class.java))
                }
                card("Provider health", "Inspect readiness, limits, failures and verified capability status.", "Open capability report") {
                    startActivity(Intent(this, OrionCapabilityReportActivity::class.java))
                }
                card("Screen recording", "Start an Android-approved capture session and review recording access.", "Recording settings") {
                    startActivity(Intent(this, SettingsFeatureActivity::class.java).putExtra(EXTRA_SECTION, "recording"))
                }
            }
            "providers" -> {
                card("Provider connections", "Connect and verify account-backed agents such as Codex and Antigravity. API-key providers remain in the encrypted capability vault.", "Manage connections") {
                    startActivity(Intent(this, AccountConnectionsActivity::class.java))
                }
                card("Chat and vision models", "Choose independent chat and vision providers, verify models, manage encrypted API keys and control fallback costs.", "Model settings") { startActivity(Intent(this, ModelSettingsActivity::class.java)) }
                card("Provider health", "Inspect readiness, limits and capability status.", "Capability report") {
                    startActivity(Intent(this, OrionCapabilityReportActivity::class.java))
                }
            }
            "voice" -> {
                val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                card("Microphone access", if(mic) "Android microphone permission is granted. You can review or revoke it in system settings." else "Microphone permission is not currently granted. Grant it through Android when you are ready to use speech input.", if(mic) "App permissions" else "Grant microphone") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                card("Voice and speech settings", "Configure spoken replies, offline speech, wake-word detection, neural voice and Gemini Live.", "Configure voice") { startActivity(Intent(this, VoiceSettingsActivity::class.java)) }
                card("Speech and live conversation", "Open the live voice experience. Speech recognition and text-to-speech availability depend on installed Android services.", "Start live conversation") {
                    startActivity(Intent(this, LiveConversationActivity::class.java))
                }
            }
            "character" -> {
                card("Character and response style", "Review Orion's fixed identity, entity philosophy and approved language list.", "Edit character") { startActivity(Intent(this, CharacterSettingsActivity::class.java)) }
            }
            "permissions" -> {
                card("App permissions", "Open Android's permission manager for PhoneAI. Review microphone, notifications, contacts and other access.", "Android app permissions") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                card("Accessibility service", "Required only for screen understanding and user-approved device actions. Enable it explicitly in Android settings.", "Accessibility settings") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                card("Notification access", "Manage notification permission and special access in Android settings.", "Notification settings") {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    startActivity(intent)
                }
                card("Orion Private Keyboard", "Enable Orion in Android's on-screen keyboard list, then select it using the system keyboard picker. Android requires you to make this choice; Orion cannot silently replace your current keyboard.", "Enable keyboard") {
                    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
                card("Switch to Orion keyboard", "Open Android's keyboard picker. Choose Orion Private Keyboard to use it; you can switch back to your previous keyboard from the same system picker.", "Choose keyboard") {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showInputMethodPicker()
                }
            }
            "recording" -> {
                card("Start screen recording", "Android displays its own consent dialog. Recording starts only after you approve the system prompt.", "Start recording") {
                    startActivity(Intent(this, RecordRequestActivity::class.java))
                }
                card("Recording permissions", "Review app permissions and the Android screen-capture consent flow.", "Review permissions") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
        }
        root.addView(label("Each action opens its real owner screen or Android's own settings. If a destination is unavailable, Orion reports the failure instead of silently redirecting.", 11f, Theme.MUTED).apply { setPadding(0, dp(18), 0, 0) })
    }
}
