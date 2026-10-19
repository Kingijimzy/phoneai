package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Orion home shell. Chat remains the primary surface; tools and configuration live in dedicated areas.
 * Built with native views to keep the experience lightweight on entry-level Android devices.
 */
class MainActivity : Activity() {
    private lateinit var content: FrameLayout
    private lateinit var statusLabel: TextView
    private var homeOrb: OrbView? = null
    private var selectedTab = "Home"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun surface(radius: Int = 22, fill: Int = Theme.PANEL): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), Theme.LINE)
        }

    private fun action(title: String, subtitle: String? = null, primary: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = surface(17, if (primary) 0xFF172D38.toInt() else Theme.PANEL)
            isClickable = true
            isFocusable = true
        }
        row.addView(label(title, 15f, if (primary) Theme.CYAN else Theme.TEXT, true))
        if (subtitle != null) {
            row.addView(label(subtitle, 12f, Theme.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }
        row.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(.985f).scaleY(.985f).setDuration(80).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
        row.setOnClickListener { onClick() }
        return row
    }

    private fun section(parent: LinearLayout, heading: String, note: String? = null): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(17), 0, dp(2))
        }
        wrap.addView(label(heading, 17f, Theme.TEXT, true))
        if (note != null) wrap.addView(label(note, 12f, Theme.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        parent.addView(wrap)
        return wrap
    }

    private fun gap(parent: LinearLayout, view: View, top: Int = 9) {
        parent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        window.decorView.systemUiVisibility = 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNav(), LinearLayout.LayoutParams(-1, dp(66)))
        setContentView(root)
        showTab(intent.getStringExtra("tab") ?: "Home", animate = false)
    }

    private fun bottomNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(5), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(0xFF0D111A.toInt())
                setStroke(dp(1), Theme.LINE)
            }
        }
        listOf("Home", "Chat", "Tools", "Settings").forEach { tab ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(7), dp(3), dp(5))
                isClickable = true
                isFocusable = true
            }
            val glyph = when (tab) { "Home" -> "⌂"; "Chat" -> "◉"; "Tools" -> "✳"; else -> "⚙" }
            cell.addView(label(glyph, 19f, if (selectedTab == tab) Theme.CYAN else Theme.MUTED, true).apply { gravity = Gravity.CENTER })
            cell.addView(label(tab, 10f, if (selectedTab == tab) Theme.CYAN else Theme.MUTED, selectedTab == tab).apply { gravity = Gravity.CENTER })
            cell.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            cell.setOnClickListener { showTab(tab) }
            bar.addView(cell)
        }
        return bar
    }

    private fun showTab(tab: String, animate: Boolean = true) {
        if (tab == "Chat") {
            selectedTab = "Chat"
            startActivity(Intent(this, MainChatActivity::class.java))
            return
        }
        selectedTab = tab
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Theme.BG)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(22), dp(19), dp(24))
        }
        scroll.addView(page)
        when (tab) {
            "Home" -> buildHome(page)
            "Tools" -> buildTools(page)
            else -> buildSettings(page)
        }
        val next = FrameLayout(this)
        next.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        next.alpha = if (animate) 0f else 1f
        next.translationY = if (animate) dp(8).toFloat() else 0f
        content.removeAllViews()
        content.addView(next, FrameLayout.LayoutParams(-1, -1))
        if (animate) next.animate().alpha(1f).translationY(0f).setDuration(190)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        // Rebuild nav selection to keep the selected state visible.
        val root = content.parent as? LinearLayout ?: return
        if (root.childCount > 1) {
            root.removeViewAt(1)
            root.addView(bottomNav(), LinearLayout.LayoutParams(-1, dp(66)))
        }
    }

    private fun buildHome(page: LinearLayout) {
        page.addView(label("ORION  /  PERSONAL AI", 11f, Theme.CYAN, true))
        page.addView(label("A little more human.\nA lot more capable.", 29f, Theme.TEXT, true).apply {
            setPadding(0, dp(9), 0, dp(5))
        })
        page.addView(label("Your thinking partner for ideas, research, memory, and phone tasks.", 13f, Theme.MUTED))
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(15))
            background = surface(26)
        }
        val avatar = OrbView(this).apply { contentDescription = "Orion animated Jarvis-style energy core" }
        homeOrb = avatar
        hero.addView(avatar, LinearLayout.LayoutParams(dp(196), dp(196)).apply { gravity = Gravity.CENTER })
        hero.addView(label("ORION IS HERE", 12f, Theme.CYAN, true).apply { gravity = Gravity.CENTER })
        hero.addView(label("Ready when you are.", 14f, Theme.TEXT).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(3)) })
        hero.addView(label("A calm, responsive assistant — with your permission at every important step.", 12f, Theme.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(7))
        })
        gap(page, hero, 19)
        gap(page, action("Start a conversation", "Chat, ask, brainstorm, or give Orion a task", true) { showTab("Chat") }, 12)
        val quick = section(page, "Quick access", "Your most-used areas, one tap away")
        gap(quick, action("Memory & routines", "Review what Orion remembers and saved workflows") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "memory"))
        }, 8)
        gap(quick, action("Skills studio", "Enable, review, and manage agent skills") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "skills"))
        }, 8)
        statusLabel = label("", 12f, Theme.MUTED)
        gap(page, statusLabel, 17)
        refreshStatus()
    }

    private fun buildTools(page: LinearLayout) {
        page.addView(label("TOOLS", 11f, Theme.CYAN, true))
        page.addView(label("A workspace for getting things done.", 25f, Theme.TEXT, true).apply { setPadding(0, dp(8), 0, dp(5)) })
        page.addView(label("Tools are separated from chat so the conversation stays focused.", 13f, Theme.MUTED))
        val device = section(page, "Phone & diagnostics", "Local utilities for your device")
        gap(device, action("Network Radar · Game Sentinel", "Connectivity, thermal, memory, and gaming-session diagnostics", true) {
            startActivity(Intent(this, DeviceUtilitiesActivity::class.java))
        }, 8)
        val agent = section(page, "Agent workspace", "Capabilities that help Orion research and work")
        gap(agent, action("Skills studio", "Manage enabled skills and their permissions") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "skills"))
        }, 8)
        gap(agent, action("Memory & routines", "Manage long-term notes and saved task flows") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "memory"))
        }, 8)
        gap(agent, action("Open Orion Browser", "Search the web inside Orion; the screen agent can interact with accessible page elements", true) {
            startActivity(Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_URL, "https://duckduckgo.com/"))
        }, 8)
        gap(agent, action("Screen recording", "Start an Android-approved screen recording") {
            startActivity(Intent(this, RecordRequestActivity::class.java))
        }, 8)
        val integrations = section(page, "Connected services", "Provider keys and integrations are managed in Settings")
        gap(integrations, action("Configure services", "AI providers, Telegram, MCP, voice, and quota controls") {
            showTab("Settings")
        }, 8)
    }

    private fun buildSettings(page: LinearLayout) {
        page.addView(label("SETTINGS", 11f, Theme.CYAN, true))
        page.addView(label("Make Orion yours.", 26f, Theme.TEXT, true).apply { setPadding(0, dp(8), 0, dp(5)) })
        page.addView(label("Organized controls, with sensitive settings kept out of the chat.", 13f, Theme.MUTED))
        gap(page, action("AI providers & models", "API keys, model selection, vision, quota saving") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "providers"))
        }, 15)
        gap(page, action("Voice & character", "Voice, speech speed, avatar, and personality") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "voice"))
        })
        gap(page, action("Memory & routines", "Manage stored facts, routines, and agent skills") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "memory"))
        })
        gap(page, action("Integrations", "Telegram, remote MCP, connection status") {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("open_section", "integrations"))
        })
        gap(page, action("Permissions & diagnostics", "Accessibility, microphone, network and device tools") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        gap(page, action("Open full-screen chat", "A separate conversation from the floating command orb") {
            startActivity(Intent(this, MainChatActivity::class.java))
        })
        gap(page, action("Open Android accessibility settings", "Required for on-screen phone control") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        page.addView(label("Orion asks before consequential actions. API keys stay on-device and are not shown in chat.", 12f, Theme.MUTED).apply {
            setPadding(0, dp(20), 0, 0)
        })
    }

    private fun refreshStatus() {
        if (!::statusLabel.isInitialized) return
        val service = PhoneAiService.instance
        val access = if (service != null) "Accessibility agent active" else "Accessibility agent not enabled"
        homeOrb?.mode = service?.displayMode() ?: OrbView.Mode.IDLE
        val keys = Providers.all.count { Prefs.hasKey(this, it.id) }
        statusLabel.text = "●  $access   ·   $keys AI provider key(s) configured"
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
}
