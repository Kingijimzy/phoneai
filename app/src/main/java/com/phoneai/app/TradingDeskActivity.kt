package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Trading Desk workspace: live pulse, coach chat, playbook, and controls.
 * Feels like a desk companion — not a settings-only screen.
 */
class TradingDeskActivity : Activity() {
    private enum class Tab { DESK, CHAT, PLAYBOOK, CONTROLS }

    private lateinit var root: LinearLayout
    private lateinit var bodyHost: LinearLayout
    private lateinit var pulseTitle: TextView
    private lateinit var pulseDetail: TextView
    private lateinit var pulseMeta: TextView
    private var tab = Tab.DESK
    private val handler = Handler(Looper.getMainLooper())
    private val turns = mutableListOf<AgentRunner.Turn>()
    private var chatMessages: LinearLayout? = null
    private var chatInput: EditText? = null
    @Volatile private var busy = false
    @Volatile private var quotesLine = "Loading quotes…"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Theme.PANEL); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Theme.LINE)
        }
    }

    private fun pill(text: String, active: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(if (active) Theme.ON_ACCENT else Theme.TEXT)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = GradientDrawable().apply {
            setColor(if (active) Theme.CYAN else Theme.PANEL2)
            cornerRadius = dp(40).toFloat()
        }
        setOnClickListener { click() }
    }

    private fun gap(h: Int) = View(this).also { it.layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turns.clear()
        turns.addAll(AgentChatStore.load(this, "coach"))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(16), dp(36), dp(16), dp(12))
        }
        setContentView(root)
        buildChrome()
        showTab(Tab.DESK)
        refreshPulse()
        refreshQuotesAsync()
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                refreshPulse()
                handler.postDelayed(this, 45_000L)
            }
        }, 45_000L)
    }

    override fun onResume() {
        super.onResume()
        refreshPulse()
        if (tab == Tab.DESK) showTab(Tab.DESK)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildChrome() {
        root.removeAllViews()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Trading Desk", 24f, Theme.TEXT, true))
        titles.addView(label("Coach · watchlist · playbook · confirmed orders", 12f, Theme.MUTED).apply {
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(pill("Settings", false) {
            startActivity(Intent(this, TradingActivity::class.java))
        })
        root.addView(header)
        root.addView(gap(12))

        val pulse = card()
        pulseTitle = label("…", 16f, Theme.TEXT, true)
        pulseDetail = label("…", 13f, Theme.MUTED).apply { setPadding(0, dp(4), 0, dp(8)) }
        pulseMeta = label("…", 11f, Theme.CYAN)
        pulse.addView(pulseTitle)
        pulse.addView(pulseDetail)
        pulse.addView(pulseMeta)
        root.addView(pulse)
        root.addView(gap(10))

        val tabs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun addTab(t: Tab, title: String) {
            tabRow.addView(pill(title, tab == t) { showTab(t) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        addTab(Tab.DESK, "Desk")
        addTab(Tab.CHAT, "Chat")
        addTab(Tab.PLAYBOOK, "Playbook")
        addTab(Tab.CONTROLS, "Controls")
        tabs.addView(tabRow)
        root.addView(tabs)
        root.addView(gap(10))

        bodyHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bodyHost, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun refreshPulse() {
        val s = TradingDeskPulse.snapshot(this)
        pulseTitle.text = s.headline
        pulseDetail.text = s.detail + "\n" + s.aliveHint
        pulseMeta.text = s.modeLine + "\n" + s.packLine + " · " + s.sessionLine + "\n" + s.playbookLine +
            "\n" + quotesLine
    }

    private fun refreshQuotesAsync() {
        Thread {
            val line = try { TradingDeskPulse.quickQuotes(this) } catch (_: Exception) { "Quotes unavailable" }
            handler.post {
                quotesLine = line
                refreshPulse()
            }
        }.start()
    }

    private fun showTab(t: Tab) {
        tab = t
        buildChrome()
        bodyHost.removeAllViews()
        when (t) {
            Tab.DESK -> renderDesk()
            Tab.CHAT -> renderChat()
            Tab.PLAYBOOK -> renderPlaybook()
            Tab.CONTROLS -> renderControls()
        }
    }

    private fun renderDesk() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val actions = card()
        actions.addView(label("Quick actions", 12f, Theme.MUTED))
        actions.addView(gap(8))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(pill("Scan setups", true) { runScan() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row1.addView(pill("Match playbook", false) { runMatch() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(row1)
        actions.addView(gap(8))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(pill("Chat coach", true) { showTab(Tab.CHAT) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        row2.addView(pill("Refresh quotes", false) { refreshQuotesAsync(); Toast.makeText(this, "Refreshing…", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(row2)
        col.addView(actions)
        col.addView(gap(10))

        val tips = card()
        tips.addView(label("How this desk works", 12f, Theme.MUTED))
        tips.addView(label(
            "• Chat here with the Trading Coach — charts, bias, mistakes.\n" +
                "• Teach setups → Accept in Playbook → Match against your list.\n" +
                "• Orders only if Execution pack is on, read-only is off, and you confirm.\n" +
                "• Testnet first. I stay with you in this workspace.",
            13f, Theme.TEXT
        ).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(tips)
        col.addView(gap(10))

        val open = card()
        open.addView(label("Open elsewhere", 12f, Theme.MUTED))
        open.addView(gap(8))
        open.addView(pill("Full coach chat (images)", false) {
            startActivity(Intent(this, AgentChatActivity::class.java).putExtra(AgentChatActivity.EXTRA_AGENT, "coach"))
        })
        open.addView(gap(8))
        open.addView(pill("Keys · limits · watch service", false) {
            startActivity(Intent(this, TradingActivity::class.java))
        })
        col.addView(open)

        scroll.addView(col, -1, -2)
        bodyHost.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun renderChat() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        chatMessages = messages
        scroll.addView(messages, -1, -2)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val chips = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Scan my watchlist" to "Scan my watchlist for setups",
            "BTC bias" to "What is the bias on BTCUSDT right now? Invalidation and uncertainty please.",
            "Session now" to "Which session is open and what should I focus on?",
            "Review risk" to "Summarize my risk limits and whether I should trade right now."
        ).forEach { (label, prompt) ->
            chipRow.addView(pill(label, false) {
                chatInput?.setText(prompt)
                sendChat()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        chips.addView(chipRow)
        col.addView(chips)
        col.addView(gap(8))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "Message the desk coach…"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            setBackgroundColor(Theme.PANEL2)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        chatInput = input
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inputRow.addView(pill("Send", true) { sendChat() }.apply {
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        col.addView(inputRow)

        bodyHost.addView(col, LinearLayout.LayoutParams(-1, -1))

        messages.removeAllViews()
        if (turns.isEmpty()) {
            addBubble(messages, false, "I'm your desk coach. Ask for a scan, a bias, or paste a setup note. I won't place orders unless Execution is on and you confirm.")
        } else {
            turns.takeLast(40).forEach { addBubble(messages, it.role == "user", it.text) }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun addBubble(host: LinearLayout, user: Boolean, text: String) {
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Theme.TEXT)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(if (user) Theme.PANEL2 else Theme.PANEL)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Theme.LINE)
            }
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
            if (user) marginStart = dp(28) else marginEnd = dp(28)
        }
        host.addView(bubble, lp)
    }

    private fun sendChat() {
        if (busy) return
        val input = chatInput ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        val messages = chatMessages ?: return
        addBubble(messages, true, text)
        turns.add(AgentRunner.Turn("user", text))
        AgentChatStore.save(this, "coach", turns)
        busy = true
        addBubble(messages, false, "Thinking…")
        val thinking = messages.getChildAt(messages.childCount - 1) as TextView
        Thread {
            val agent = AgentProfiles.find("coach") ?: AgentProfiles.all.first()
            val history = turns.dropLast(1).takeLast(12)
            val reply = try {
                AgentRunner.runFull(this, agent, history, text, null).text
            } catch (e: Exception) {
                "I hit an error: ${e.message?.take(160)}. Try again or open full coach chat."
            }
            handler.post {
                busy = false
                thinking.text = reply.ifBlank { "No answer came back." }
                turns.add(AgentRunner.Turn("assistant", thinking.text.toString()))
                AgentChatStore.save(this@TradingDeskActivity, "coach", turns)
                refreshPulse()
            }
        }.start()
    }

    private fun renderPlaybook() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rules = TradingDeskStore.rules(this)
        val cands = TradingDeskStore.candidates(this)

        val summary = card()
        summary.addView(label("Playbook", 12f, Theme.MUTED))
        summary.addView(label(
            "${rules.count { it.enabled }} enabled · ${rules.size} rules · ${cands.size} pending",
            15f, Theme.TEXT, true
        ).apply { setPadding(0, dp(6), 0, 0) })
        summary.addView(gap(8))
        summary.addView(pill("Match watchlist now", true) { runMatch() })
        col.addView(summary)
        col.addView(gap(10))

        if (cands.isNotEmpty()) {
            val pend = card()
            pend.addView(label("Pending Accept", 12f, Theme.MUTED))
            cands.take(8).forEach { (id, c) ->
                pend.addView(label("$id · ${c.instrument} ${c.direction}", 14f, Theme.TEXT, true).apply { setPadding(0, dp(8), 0, 0) })
                pend.addView(label(c.name + " · " + c.conditions.take(3).joinToString(", "), 12f, Theme.MUTED))
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(pill("Accept", true) {
                    TradingDeskStore.acceptCandidate(this, id, true)
                    Toast.makeText(this, "Accepted $id", Toast.LENGTH_SHORT).show()
                    showTab(Tab.PLAYBOOK)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                row.addView(pill("Reject", false) {
                    TradingDeskStore.rejectCandidate(this, id)
                    showTab(Tab.PLAYBOOK)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                pend.addView(row)
            }
            col.addView(pend)
            col.addView(gap(10))
        }

        val live = card()
        live.addView(label("Rules", 12f, Theme.MUTED))
        if (rules.isEmpty()) {
            live.addView(label("No rules yet. Chat a setup note or use the coach, then Accept here.", 13f, Theme.TEXT).apply { setPadding(0, dp(6), 0, 0) })
        } else {
            rules.forEach { r ->
                live.addView(label("${r.instrument} ${r.direction} · ${r.name}", 14f, Theme.TEXT, true).apply { setPadding(0, dp(8), 0, 0) })
                live.addView(label("${if (r.enabled) "ON" else "off"} · ${r.conditions.take(3).joinToString(", ")}", 12f, Theme.MUTED))
                live.addView(pill(if (r.enabled) "Disable" else "Enable", r.enabled) {
                    TradingDeskStore.setRuleEnabled(this, r.id, !r.enabled)
                    showTab(Tab.PLAYBOOK)
                })
            }
        }
        col.addView(live)
        scroll.addView(col, -1, -2)
        bodyHost.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun renderControls() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = TradingDeskPulse.snapshot(this)

        val status = card()
        status.addView(label("Desk status", 12f, Theme.MUTED))
        status.addView(label(s.modeLine, 14f, Theme.TEXT, true).apply { setPadding(0, dp(6), 0, 0) })
        status.addView(label(s.packLine, 13f, Theme.MUTED))
        status.addView(label("Max order ${Prefs.maxOrderUsd(this)} USDT", 13f, Theme.MUTED))
        col.addView(status)
        col.addView(gap(10))

        val nav = card()
        nav.addView(label("Open", 12f, Theme.MUTED))
        nav.addView(gap(8))
        nav.addView(pill("Trading settings (keys, watch, limits)", true) {
            startActivity(Intent(this, TradingActivity::class.java))
        })
        nav.addView(gap(8))
        nav.addView(pill("Agent Control (packs & approval)", false) {
            startActivity(Intent(this, AgentControlActivity::class.java))
        })
        nav.addView(gap(8))
        nav.addView(pill("Full coach (charts / images)", false) {
            startActivity(Intent(this, AgentChatActivity::class.java).putExtra(AgentChatActivity.EXTRA_AGENT, "coach"))
        })
        col.addView(nav)
        col.addView(gap(10))

        val note = card()
        note.addView(label("Automation boundary", 12f, Theme.MUTED))
        note.addView(label(
            "I can scan, match, coach, and propose. I do not silently place orders. " +
                "Enable Trading execution in Agent Control, turn off read-only, then confirm each order.",
            13f, Theme.TEXT
        ).apply { setPadding(0, dp(6), 0, 0) })
        col.addView(note)

        scroll.addView(col, -1, -2)
        bodyHost.addView(scroll, LinearLayout.LayoutParams(-1, -1))
    }

    private fun runScan() {
        Toast.makeText(this, "Scanning watchlist…", Toast.LENGTH_SHORT).show()
        Thread {
            val found = try { Scanner.scan(this).take(6) } catch (_: Exception) { emptyList() }
            handler.post {
                val msg = if (found.isEmpty()) "No high-score setups right now."
                else found.joinToString("\n\n") {
                    "${it.symbol} ${it.tf} ${it.side} ${it.type}\nentry ${it.entry} stop ${it.stop} target ${it.target} score ${it.score}"
                }
                AlertDialog.Builder(this).setTitle("Scanner").setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
    }

    private fun runMatch() {
        Toast.makeText(this, "Matching playbook…", Toast.LENGTH_SHORT).show()
        Thread {
            val cards = try { TradingDeskStore.matchWatchlist(this) } catch (_: Exception) { emptyList() }
            handler.post {
                val msg = if (cards.isEmpty()) "No playbook matches. Accept rules and keep symbols on the watchlist."
                else cards.take(8).joinToString("\n\n") {
                    "${it.instrument} ${it.direction} score ${"%.2f".format(it.score)} — ${it.ruleName}\n${it.reasons.take(3).joinToString(", ")}"
                }
                AlertDialog.Builder(this).setTitle("Playbook match").setMessage(msg).setPositiveButton("OK", null).show()
                refreshPulse()
            }
        }.start()
    }
}
