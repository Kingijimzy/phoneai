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
 * Dedicated graphite landing pages for settings domains that previously routed
 * into the legacy settings catalogue. Existing editors remain the source of
 * truth until their controls are migrated; these pages provide clear, safe entry
 * points and never pretend a setting changed when Android or a provider refused.
 */
class SettingsFeatureActivity : Activity() {
    companion object {
        const val EXTRA_SECTION = "settings_feature"
        private val VALID = setOf("providers", "voice", "character", "permissions", "recording")
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }
    private fun surface() = GradientDrawable().apply {
        setColor(0xFF171717.toInt()); cornerRadius = dp(15).toFloat()
        setStroke(dp(1), 0xFF383838.toInt())
    }
    private fun card(title: String, detail: String, actionLabel: String, action: () -> Unit) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13))
            background = surface()
        }
        block.addView(label(title, 16f, 0xFFF1F1F1.toInt(), true))
        block.addView(label(detail, 12f, 0xFFAAAAAA.toInt()).apply { setPadding(0, dp(5), 0, dp(11)) })
        val button = TextView(this).apply {
            text = "$actionLabel  ›"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(0xFFF0F0F0.toInt()); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF292929.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF505050.toInt())
            }
            isClickable = true; isFocusable = true
            setOnClickListener { runCatching(action).onFailure {
                Toast.makeText(this@SettingsFeatureActivity, "Couldn't open this feature: ${it.message ?: "please try again"}", Toast.LENGTH_LONG).show()
            } }
        }
        block.addView(button)
        root.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
    }
    private lateinit var root: LinearLayout
    private var section = "providers"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        section = (intent.getStringExtra(EXTRA_SECTION) ?: "providers").lowercase()
        if (section !in VALID) section = "providers"
        window.statusBarColor = 0xFF090909.toInt()
        window.navigationBarColor = 0xFF090909.toInt()
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF090909.toInt()); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(22), dp(17), dp(30))
        }
        scroll.addView(root); setContentView(scroll)
        render()
    }
    private fun openLegacy(key: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", key))
    }
    private fun render() {
        val heading = when(section) {
            "providers" -> Triple("Providers & models", "Choose the models and connections Orion uses. Keys stay in the encrypted capability vault.", "AI CONNECTIONS")
            "voice" -> Triple("Voice & speech", "Configure speech input, microphone access, spoken responses and live conversation.", "VOICE EXPERIENCE")
            "character" -> Triple("Character & response style", "Adjust Orion's response personality and presentation without changing your provider setup.", "ASSISTANT STYLE")
            "permissions" -> Triple("Permissions & device access", "Review Android permissions and system access. Android owns these switches; Orion cannot silently grant them.", "DEVICE ACCESS")
            else -> Triple("Screen recording", "Start an Android-approved capture session and review recording access.", "CAPTURE")
        }
        root.addView(TextView(this).apply {
            text = "‹   Back"; textSize = 14f; setTextColor(0xFFD6D6D6.toInt())
            setPadding(0, dp(3), 0, dp(16)); isClickable = true
            setOnClickListener { finish() }
        })
        root.addView(label("ORION  /  SETTINGS", 10f, 0xFFAAAAAA.toInt(), true))
        root.addView(label(heading.first, 27f, 0xFFF4F4F4.toInt(), true).apply { setPadding(0, dp(7), 0, dp(5)) })
        root.addView(label(heading.second, 13f, 0xFFAAAAAA.toInt()).apply { setPadding(0, 0, 0, dp(12)) })
        root.addView(label(heading.third, 10f, 0xFFBDBDBD.toInt(), true).apply { letterSpacing = .12f })
        when(section) {
            "providers" -> {
                card("Provider connections", "Connect, replace or verify API credentials. Connection status and token management live in the capability board.", "Open connections") {
                    startActivity(Intent(this, CapabilityBoardActivity::class.java))
                }
                card("Chat and vision models", "Choose the chat provider, model, vision model and fallback order.", "Model settings") { openLegacy("providers") }
                card("Provider health", "Inspect readiness, limits and capability status.", "Capability report") {
                    startActivity(Intent(this, OrionCapabilityReportActivity::class.java))
                }
            }
            "voice" -> {
                val mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                card("Microphone access", if(mic) "Android microphone permission is granted." else "Microphone permission is not currently granted. Orion will ask Android when you enable it.", "Voice settings") { openLegacy("voice") }
                card("Speech and live conversation", "Open the live voice experience. Speech recognition and text-to-speech availability depend on installed Android services.", "Start live conversation") {
                    startActivity(Intent(this, LiveConversationActivity::class.java))
                }
                card("Voice configuration", "Speech engine, voice output and interaction preferences.", "Configure voice") { openLegacy("voice") }
            }
            "character" -> {
                card("Response personality", "Choose the assistant character and response style. Your selection is stored by Orion's existing settings.", "Edit character") { openLegacy("character") }
                card("Language and presentation", "Review language and response formatting preferences.", "Open style controls") { openLegacy("character") }
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
        root.addView(label("Each action opens its real owner screen or Android's own settings. If a destination is unavailable, Orion reports the failure instead of silently redirecting.", 11f, 0xFF858585.toInt()).apply { setPadding(0, dp(18), 0, 0) })
    }
}
