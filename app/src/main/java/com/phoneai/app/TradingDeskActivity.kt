package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Trading workspace backed by Orion's canonical TradingDesk model. Coaching stays in central chat. */
class TradingDeskActivity : Activity() {
    private enum class Tab { DESK, PLAYBOOK, EVIDENCE }
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var pulse: TextView
    private var selected = Tab.DESK

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 13f, color: Int = Theme.TEXT, bold: Boolean = false) =
        TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(dp(2).toFloat(), 1f) }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Theme.LINE) }
    }
    private fun gap(h: Int) = View(this).also { it.layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun action(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Theme.ON_ACCENT else Theme.TEXT)
        setPadding(dp(13), dp(11), dp(13), dp(11))
        background = GradientDrawable().apply { setColor(if (primary) Theme.CYAN else Theme.PANEL2); cornerRadius = dp(13).toFloat(); setStroke(dp(1), Theme.LINE) }
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = savedInstanceState?.getString("trading_desk_tab")?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.DESK
        buildShell()
        render(selected)
        refreshPulse()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("trading_desk_tab", selected.name)
        super.onSaveInstanceState(outState)
    }

    private fun buildShell() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Theme.BG); setPadding(dp(16), dp(12), dp(16), dp(8)) }
        setContentView(root)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(action("‹ Back") { finish() })
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
            addView(text("Trading Desk", 20f, Theme.TEXT, true))
            addView(text("Markets · strategy · journal · risk", 10f, Theme.MUTED))
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(action("Orion Chat", true) { openTradingChat("Review my current trading context and ask what I want to analyse. Do not assume a setup or place an order.") })
        root.addView(header); root.addView(gap(12))
        pulse = text("Loading trading workspace status…", 11f, Theme.MUTED)
        root.addView(card().apply { addView(pulse) }); root.addView(gap(10))
        val tabs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(Tab.DESK to "Desk", Tab.PLAYBOOK to "Playbook", Tab.EVIDENCE to "Performance").forEach { (tab, label) ->
            row.addView(action(label, selected == tab) { render(tab) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        tabs.addView(row); root.addView(tabs); root.addView(gap(10))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body, -1, -2) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun refreshPulse() {
        Thread {
            val line = runCatching { TradingDesk.statusLine(this) }.getOrDefault("Trading desk status unavailable")
            runOnUiThread { if (!isFinishing && !isDestroyed) pulse.text = line }
        }.start()
    }
    private fun render(tab: Tab) {
        selected = tab; body.removeAllViews()
        when (tab) { Tab.DESK -> renderDesk(); Tab.PLAYBOOK -> renderPlaybook(); Tab.EVIDENCE -> renderEvidence() }
    }

    private fun renderDesk() {
        val symbols = TradingDesk.followSymbols(this)
        body.addView(card().apply {
            addView(text("Your market workspace", 16f, Theme.TEXT, true)); addView(gap(5))
            addView(text(if (symbols.isEmpty()) "Your watchlist is empty. Add instruments in Trading controls or ask Orion to build one with you." else "Following: " + symbols.joinToString(" · "), 12f, Theme.MUTED))
            addView(gap(10))
            addView(action("Ask Orion to analyse my markets", true) {
                openTradingChat("Enter Trading Desk context. Review my watchlist and current market conditions, then ask which instrument and timeframe I want to analyse. Use current evidence, state uncertainty and invalidation. Do not place orders.")
            })
        })
        body.addView(gap(10))
        body.addView(card().apply {
            addView(text("Live research", 14f, Theme.TEXT, true))
            addView(text("Read-only market analysis. Results are fetched when requested; this screen does not claim continuous monitoring.", 11f, Theme.MUTED))
            addView(gap(8))
            addView(action("Scan watchlist for setups") { runDeskAction("Scanning watchlist…") { TradingDesk.scan(this, TradingDesk.followSymbols(this)) } })
            addView(gap(7))
            addView(action("Check market conditions & news") { runDeskAction("Checking market conditions…") { TradingDesk.conditions(this, null) } })
            addView(gap(7))
            addView(action("Review positions") { runDeskAction("Reading position data…") { TradingDesk.positionsText(this) } })
            addView(gap(7))
            addView(action("Update paper-trading results") { runDeskAction("Updating simulated trades…") { TradingDesk.updatePaper(this) } })
        })
        body.addView(gap(10))
        body.addView(card().apply {
            addView(text("Execution & safety", 14f, Theme.TEXT, true))
            addView(text("Trading research is separate from order execution. Real orders remain subject to the execution capability, read-only setting, risk checks and a fresh confirmation. The kill switch is ${if (TradingDesk.killSwitch(this@TradingDeskActivity)) "ON" else "OFF"}.", 12f, Theme.MUTED))
            addView(gap(8))
            addView(action("Open trading controls & account settings") { startActivity(Intent(this@TradingDeskActivity, TradingActivity::class.java)) })
        })
        body.addView(gap(10))
        body.addView(card().apply {
            addView(text("Trading Coach in Orion Chat", 14f, Theme.TEXT, true))
            addView(text("Chart uploads, SMC discussion, trade reviews, journal context and specialist analysis happen in your main Orion conversation. You won't be sent to a separate coach transcript.", 12f, Theme.MUTED))
            addView(gap(8))
            addView(action("Open the central trading conversation", true) {
                openTradingChat("Continue in Trading Desk mode. Keep the trading discussion in this central Orion conversation. Help me analyse charts, SMC structure, liquidity, setups, risk and journal lessons. Ask for missing chart or timeframe context; never claim live data you have not fetched.")
            })
        })
    }

    private fun renderPlaybook() {
        val pending = TradingDesk.pending(this)
        val active = TradingDesk.strategies(this)
        body.addView(card().apply {
            addView(text("Strategy playbook", 16f, Theme.TEXT, true))
            addView(text("${active.size} accepted strategies · ${pending.size} proposals awaiting your decision", 12f, Theme.MUTED))
            addView(gap(8))
            addView(action("Ask Orion to draft a strategy from my rules") {
                openTradingChat("Help me turn my trading rules into a structured strategy proposal. Ask for instrument, timeframe, setup conditions, minimum reward-to-risk and invalidation. Do not activate it without my approval.")
            })
        })
        body.addView(gap(10))
        if (pending.isEmpty()) body.addView(card().apply { addView(text("No pending strategy proposals.", 12f, Theme.MUTED)) })
        pending.forEach { strategy ->
            body.addView(card().apply {
                addView(text(strategy.name, 14f, Theme.TEXT, true))
                addView(text(
                    "Instruments: ${strategy.instruments.joinToString(", ").ifBlank { "not specified" }}\n" +
                    "Timeframes: ${strategy.timeframes.joinToString(", ").ifBlank { "not specified" }}\n" +
                    "Setups: ${strategy.setupTypes.joinToString(", ").ifBlank { "not specified" }}\n" +
                    "Minimum R:R: ${strategy.minRewardToRisk} · Risk target: ${strategy.riskPct}%\n" + strategy.notes,
                    11f, Theme.MUTED
                ))
                addView(gap(8))
                addView(action("Accept strategy") {
                    AlertDialog.Builder(this@TradingDeskActivity).setTitle("Accept strategy?")
                        .setMessage("This adds the proposal to your playbook. It does not place a trade.")
                        .setPositiveButton("Accept") { _, _ ->
                            val accepted = TradingDesk.acceptPending(this@TradingDeskActivity, strategy.name)
                            android.widget.Toast.makeText(this@TradingDeskActivity, if (accepted) "Strategy accepted" else "Proposal no longer available", android.widget.Toast.LENGTH_SHORT).show()
                            render(Tab.PLAYBOOK)
                        }.setNegativeButton("Cancel", null).show()
                })
                addView(gap(6))
                addView(action("Discuss with Orion") {
                    openTradingChat("Review this proposed strategy before I decide: ${strategy.name}. Instruments: ${strategy.instruments.joinToString()}. Timeframes: ${strategy.timeframes.joinToString()}. Setups: ${strategy.setupTypes.joinToString()}. Minimum R:R: ${strategy.minRewardToRisk}. Risk target: ${strategy.riskPct}%. Notes: ${strategy.notes}. Identify ambiguity and risks; do not activate it.")
                })
            })
            body.addView(gap(9))
        }
        if (active.isNotEmpty()) body.addView(card().apply {
            addView(text("Accepted strategies", 14f, Theme.TEXT, true))
            active.forEach { strategy ->
                addView(text("• ${strategy.name} — ${strategy.instruments.joinToString(", ")} · ${strategy.timeframes.joinToString(", ")} · min R:R ${strategy.minRewardToRisk}", 11f, Theme.MUTED))
            }
        })
    }

    private fun renderEvidence() {
        body.addView(card().apply {
            addView(text("Performance evidence", 16f, Theme.TEXT, true))
            addView(text(TradingDesk.evidenceText(this@TradingDeskActivity), 12f, Theme.MUTED))
            addView(gap(8))
            addView(action("Review my trading habits with Orion", true) {
                openTradingChat("Review my saved trading performance evidence and habits. Separate recorded facts from assumptions, identify sample-size limitations, and suggest one improvement to test. Do not invent results.")
            })
        })
        body.addView(gap(10))
        body.addView(card().apply {
            addView(text("Position and account snapshot", 14f, Theme.TEXT, true))
            addView(action("Refresh positions") { runDeskAction("Refreshing positions…") { TradingDesk.positionsText(this@TradingDeskActivity) } })
        })
    }

    private fun runDeskAction(loading: String, job: () -> String) {
        val output = text(loading, 12f, Theme.MUTED).apply { setPadding(0, dp(10), 0, dp(6)) }
        body.addView(output)
        Thread {
            val result = runCatching { job() }.getOrElse { "Could not complete request: ${it.message ?: it.javaClass.simpleName}" }
            runOnUiThread { if (!isFinishing && !isDestroyed) output.text = result }
        }.start()
    }

    private fun openTradingChat(prompt: String) {
        startActivity(Intent(this, MainChatActivity::class.java).putExtra(MainChatActivity.EXTRA_INITIAL_PROMPT, prompt))
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, TradingDeskActivity::class.java)
    }
}
