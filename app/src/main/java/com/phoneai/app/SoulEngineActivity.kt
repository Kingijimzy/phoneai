package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Interactive executive dashboard built from local app state, not optimistic feature labels. */
class SoulEngineActivity : Activity() {
    private lateinit var page: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var summaryLine: TextView
    private lateinit var runtimeLine: TextView
    private lateinit var loopLine: TextView
    private lateinit var selfTestLine: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(3))
        }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(13), dp(15), dp(13))
        background = GradientDrawable().apply {
            setColor(0xFF171717.toInt()); cornerRadius = dp(17).toFloat()
            setStroke(dp(1), 0xFF353535.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun action(caption: String, detail: String? = null, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = GradientDrawable().apply {
                setColor(0xFF222222.toInt()); cornerRadius = dp(13).toFloat()
                setStroke(dp(1), 0xFF414141.toInt())
            }
            isClickable = true; isFocusable = true; contentDescription = caption
            addView(label(caption, 14f, 0xFFF0F0F0.toInt(), true))
            if (!detail.isNullOrBlank()) addView(label(detail, 11f, 0xFFAAAAAA.toInt()))
            setOnClickListener { onClick() }
        }
    private fun openChat(prompt: String) {
        startActivity(Intent(this, MainChatActivity::class.java).putExtra(MainChatActivity.EXTRA_INITIAL_PROMPT, prompt))
    }
    private fun openSettings(section: String? = null) {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            if (section != null) putExtra("open_section", section)
        })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090909.toInt(); window.navigationBarColor = 0xFF090909.toInt()
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF090909.toInt()); isFillViewport = true; clipToPadding = false }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(30)) }
        scroll.addView(page); setContentView(scroll)
        buildPage(); refreshSnapshot()
    }
    override fun onResume() { super.onResume(); if (::page.isInitialized) refreshSnapshot() }

    private fun buildPage() {
        page.removeAllViews()
        page.addView(label("ORION / EXECUTIVE SYSTEM", 10f, 0xFFAAAAAA.toInt(), true))
        page.addView(label("Soul Engine", 29f, 0xFFF4F4F4.toInt(), true))
        page.addView(label("The coordination layer for understanding requests, checking capabilities, routing work, learning from outcomes, and verifying results.", 13f, 0xFFAAAAAA.toInt()))
        val hero = card()
        hero.addView(label("LIVE SYSTEM SNAPSHOT", 10f, 0xFFAAAAAA.toInt(), true))
        statusLine = label("Checking local readiness…", 17f, 0xFFE4E4E4.toInt(), true)
        summaryLine = label("", 12f, 0xFFBDBDBD.toInt())
        runtimeLine = label("", 12f, 0xFFBDBDBD.toInt())
        loopLine = label("Runtime session: checking…", 12f, 0xFFBDBDBD.toInt())
        hero.addView(statusLine); hero.addView(summaryLine); hero.addView(runtimeLine); hero.addView(loopLine)
        hero.addView(Button(this).apply {
            text = "Refresh live status"; isAllCaps = false
            setOnClickListener { refreshSnapshot(); Toast.makeText(this@SoulEngineActivity, "Local status refreshed", Toast.LENGTH_SHORT).show() }
        })
        page.addView(hero)

        val brain = card()
        brain.addView(label("EXECUTIVE ACTIONS", 10f, 0xFFAAAAAA.toInt(), true))
        brain.addView(action("Ask Orion to diagnose the whole platform", "Inspect integrations and report evidence-backed gaps.") {
            openChat("Run a whole-platform integration audit of this PhoneAI app using only capabilities actually available to you. Inspect connected settings, Soul Engine, agent delegation, Teach Mode and learned lessons, task centre and recovery, local memory, local knowledge graph/documents, provider and service connection states, terminal/shell, voice, and Trading Desk. Test safe read-only paths where possible. Distinguish source/configuration from live verification, identify exact disconnected flows, repair only through supported tools and approvals, and report each item as VERIFIED, FAILED, or NOT VERIFIED with evidence. Do not claim a task succeeded without checking its result.")
        })
        brain.addView(action("Run local self-test", "Runs built-in diagnostics without changing permissions or sending trades.") {
            runSelfTest()
        }.apply { layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8) } })
        selfTestLine = label("Self-test has not been run in this session.", 11f, 0xFFAAAAAA.toInt())
        brain.addView(selfTestLine)
        brain.addView(action("Open full capability report", "Readiness, configured services and local execution evidence.") {
            startActivity(Intent(this, OrionCapabilityReportActivity::class.java))
        }.apply { layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(8) } })
        page.addView(brain)

        val systems = card()
        systems.addView(label("CONNECTED WORKSPACES", 10f, 0xFFAAAAAA.toInt(), true))
        systems.addView(label("Open each workspace directly. Settings remain authoritative; this dashboard never silently enables permissions or live actions.", 12f, 0xFFAAAAAA.toInt()))
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun addRow(vararg entries: Pair<String, () -> Unit>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            entries.forEachIndexed { index, entry ->
                row.addView(action(entry.first, null, entry.second), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index > 0) leftMargin = dp(7)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        addRow("Agents" to { startActivity(Intent(this, AgentsActivity::class.java)) },
               "Teach Mode" to { startActivity(Intent(this, TeachModeActivity::class.java)) })
        addRow("Task Centre" to { startActivity(Intent(this, TaskCenterActivity::class.java)) },
               "Memory" to { startActivity(Intent(this, MemoryActivity::class.java)) })
        addRow("Knowledge base" to { openSettings("knowledge") },
               "Knowledge graph" to { startActivity(Intent(this, KnowledgeGraphActivity::class.java)) })
        addRow("Trading Desk" to { startActivity(Intent(this, TradingActivity::class.java)) },
               "Terminal / shell" to { startActivity(Intent(this, TerminalActivity::class.java)) })
        addRow("Capability board" to { startActivity(Intent(this, CapabilityBoardActivity::class.java)) },
               "Settings" to { openSettings() })
        addRow("Main chat" to { startActivity(Intent(this, MainChatActivity::class.java)) },
               "Teach a correction" to { openChat("Explain how to record a correction or successful/failed workflow as a reviewable local lesson in Teach Mode. Do not claim that the base model is retrained.") })
        systems.addView(grid); page.addView(systems)

        val rules = card()
        rules.addView(label("HONEST OPERATING RULES", 10f, 0xFFAAAAAA.toInt(), true))
        rules.addView(label("• Saved credentials are not verified connections.\n• A queued task is not proof that work is running.\n• Tool output is not completion proof when verification is required.\n• External sign-in, sensitive phone actions and live trading still require appropriate user approval.\n• Local memory and knowledge remain on this device unless a feature explicitly sends selected content to an external provider.", 12f, 0xFFD0D0D0.toInt()))
        page.addView(rules)
    }

    private fun refreshSnapshot() {
        if (!::statusLine.isInitialized) return
        val tools = runCatching { ToolRegistry.all.size }.getOrDefault(0)
        val packs = runCatching { AgentPolicy.packs.count { it.implemented } }.getOrDefault(0)
        val enabled = runCatching { AgentPolicy.packs.count { it.implemented && AgentPolicy.enabled(this, it.id) } }.getOrDefault(0)
        val providerKeys = runCatching { Providers.all.count { Prefs.hasKey(this, it.id) } }.getOrDefault(0)
        val services = runCatching { OrionConnectionStore.list(this) }.getOrDefault(emptyList())
        val verified = services.count { it.secretAvailable && it.verified }
        val savedTasks = runCatching { TaskStore.all(this).size }.getOrDefault(0)
        val memoryCount = runCatching { MemoryStore.all(this).size }.getOrDefault(0)
        val knowledgeCount = runCatching { KnowledgeStore.list(this).size }.getOrDefault(0)
        val serviceReady = PhoneAiService.instance != null
        val shellReady = runCatching { ShellRunner.runBuiltin(this, "pwd").startsWith("workspace/") }.getOrDefault(false)
        val status = when {
            tools == 0 || packs == 0 -> "BLOCKED · core registry unavailable"
            !serviceReady || enabled == 0 -> "LIMITED · service or capability packs need attention"
            else -> "READY FOR CHECKS · live workflows still require testing"
        }
        statusLine.text = status
        statusLine.setTextColor(if (status.startsWith("READY")) 0xFF9ED6A2.toInt() else 0xFFFFC078.toInt())
        summaryLine.text = tools.toString() + " registered tools · " + enabled + "/" + packs + " implemented packs enabled · " + providerKeys + " provider keys saved"
        runtimeLine.text = verified.toString() + "/" + services.count { it.secretAvailable } + " saved service connections verified · " + savedTasks + " saved tasks · " + memoryCount + " memories · " + knowledgeCount + " knowledge docs · built-in shell " + (if (shellReady) "ready" else "limited") + " · accessibility service " + (if (serviceReady) "connected" else "not connected")
        val snapshot = PhoneAiService.instance?.soulRuntimeSnapshot()
        loopLine.text = if (snapshot == null) {
            "Runtime session: NOT RUNNING · enable Orion's accessibility service to start the local queue"
        } else {
            "Runtime session: " + snapshot.state + " · " + snapshot.queued + " queued · last event: " + snapshot.lastEvent
        }
    }

    private fun runSelfTest() {
        selfTestLine.text = "Running local self-test…"
        Thread {
            val result = try { SelfTest.format(SelfTest.run(applicationContext)) }
            catch (e: Exception) { "Self-test failed: " + (e.message ?: e.javaClass.simpleName) }
            runOnUiThread { if (!isFinishing && !isDestroyed) selfTestLine.text = result.take(2600) }
        }.start()
    }
}
