package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Trading control room: keys, practice/real mode, limits and the background market watch. Orion's monochrome card style. */
class TradingActivity : Activity() {
    private lateinit var host: LinearLayout
    private var firstRender = true
    private var simStrategy = ""
    private var simSymbol = ""
    private var simTf = "1h"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { setColor(Theme.PANEL); cornerRadius = dp(24).toFloat(); setStroke(dp(1), Theme.LINE) }
    }

    private fun pill(text: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(if (on) Theme.ON_ACCENT else Theme.TEXT)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = GradientDrawable().apply { setColor(if (on) Theme.CYAN else Theme.PANEL2); cornerRadius = dp(40).toFloat() }
        setOnClickListener { click() }
    }

    private fun gap(h: Int) = View(this).also { it.layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG); isFillViewport = true }
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(40), dp(18), dp(28)) }
        scroll.addView(host, -1, -2)
        setContentView(scroll)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun row(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        v.forEachIndexed { i, x -> addView(x, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(8) }) }
    }

    private fun render() {
        host.removeAllViews()
        val test = Prefs.bybitTestnet(this)
        host.addView(label("TRADING", 11f, Theme.MUTED, true))
        host.addView(label("Desk", 30f, Theme.TEXT, true).apply { setPadding(0, dp(4), 0, dp(14)) })
        host.addView(row(
            pill("Back to chat", true) { openWithFade(AgentChatActivity.workspaceIntent(this)) },
            pill("Orion brain", false) { openWithFade(Intent(this, SoulEngineActivity::class.java)) }
        ))
        host.addView(label("Settings, playbook, simulation and safety for your trading chat. The chat is the front door: talk to it, upload a chart, accept what it proposes.", 12f, Theme.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        host.addView(gap(12))
        host.addView(playbookCard(), LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))
        host.addView(deskCard(), LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))
        host.addView(positionsCard(), LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))
        host.addView(chainCard(), LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))
        host.addView(simulateCard(), LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))
        host.addView(conditionsCard(), LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        val acc = card()
        acc.addView(label("Account", 12f, Theme.MUTED))
        acc.addView(label(if (Bybit.hasKeys(this)) "Keys saved" else "No keys yet", 20f, Theme.TEXT, true))
        acc.addView(label(if (test) "Practice mode (fake money)" else "REAL MONEY mode", 13f, if (test) Theme.MUTED else Theme.DANGER).apply { setPadding(0, dp(4), 0, dp(12)) })
        acc.addView(row(
            pill("Set API keys", false) { keyDialog() },
            pill(if (test) "Go real" else "Back to practice", !test) { toggleMode() }
        ))
        host.addView(acc, LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        val modeCard = card()
        modeCard.addView(label("TRADING EXECUTION", 12f, Theme.MUTED, true))
        modeCard.addView(label(if (Prefs.tradingReadOnly(this)) "READ-ONLY · analysis and alerts" else "ORDER PLACEMENT ENABLED", 16f, Theme.TEXT, true).apply { setPadding(0, dp(5), 0, dp(4)) })
        modeCard.addView(label("Read-only is the default. Enabling orders does not automate trading: each order still requires a fresh confirmation and existing limits.", 12f, Theme.MUTED))
        val readOnlySwitch = Switch(this).apply {
            text = "Block all order placement"
            setTextColor(Theme.TEXT)
            isChecked = Prefs.tradingReadOnly(this@TradingActivity)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled) {
                    Prefs.setTradingReadOnly(this@TradingActivity, true)
                    render()
                } else {
                    AlertDialog.Builder(this@TradingActivity)
                        .setTitle("Enable confirmed order placement?")
                        .setMessage("This permits order proposals to reach the existing confirmation and risk-limit checks. It does not enable automatic trading. Never use an API key with withdrawal permission.")
                        .setNegativeButton("Keep read-only") { _, _ -> render() }
                        .setPositiveButton("Enable orders") { _, _ -> Prefs.setTradingReadOnly(this@TradingActivity, false); render() }
                        .show()
                }
            }
        }
        modeCard.addView(readOnlySwitch)
        host.addView(modeCard, LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        val watch = card()
        val sw = Switch(this).apply {
            text = "Market watch (works with screen off)"; setTextColor(Theme.TEXT)
            isChecked = Prefs.watchOn(this@TradingActivity)
            setOnCheckedChangeListener { _, on -> if (on) WatchService.start(this@TradingActivity) else WatchService.stop(this@TradingActivity) }
        }
        watch.addView(sw)
        watch.addView(label("Checks " + Prefs.watchlist(this).replace("USDT", "") + " every " + Prefs.watchMinutes(this) + " min and alerts you.", 12f, Theme.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        host.addView(watch, LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        val history = card()
        history.addView(label("Imported trade history", 12f, Theme.MUTED))
        history.addView(label(TradeHistoryStore.summaryText(this), 13f, Theme.TEXT).apply { setPadding(0, dp(6), 0, dp(10)) })
        history.addView(label("Import a CSV export from MT5, Bybit or another platform. The selected file is read locally; only normalized trade fields are saved. No provider upload or order placement occurs.", 11f, Theme.MUTED).apply { setPadding(0, 0, 0, dp(8)) })
        history.addView(row(
            pill("Import CSV", false) { startTradeImport() },
            pill("Review stats", true) { reviewTradeHistory() }
        ))
        host.addView(history, LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        host.addView(derivCard(), LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        val lim = card()
        lim.addView(label("Safety limits", 12f, Theme.MUTED))
        lim.addView(label("Max order " + Prefs.maxOrderUsd(this).toInt() + " USD, max risk " + Prefs.maxRiskPct(this) + "% of balance", 15f, Theme.TEXT, true).apply { setPadding(0, dp(4), 0, dp(6)) })
        lim.addView(label("Every order needs your yes and a stop loss. Say \"set max order to 50\" in chat to change.", 12f, Theme.MUTED))
        host.addView(lim, LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        val help = card()
        help.addView(label("Try saying", 12f, Theme.MUTED))
        help.addView(label("my balance · scan the market · analyse BTC · buy 20 dollars of ETH · close all · my stats", 14f, Theme.TEXT).apply { setPadding(0, dp(6), 0, 0) })
        host.addView(help, LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(16))
        host.addView(label("Use a Read + Trade key. Never enable Withdraw.", 11f, Theme.MUTED))
        if (firstRender) {
            firstRender = false
            for (i in 0 until host.childCount) if (host.getChildAt(i) is LinearLayout) Motion.enter(host.getChildAt(i), i / 2)
        }
    }

    /** Open positions with risk flags, trade sync with habit review, and crypto funding and open interest. */
    private fun positionsCard(): LinearLayout {
        val c = card()
        c.addView(label("POSITIONS, HABITS & DERIVATIVES", 12f, Theme.MUTED, true))
        val out = label("Watch runs these in the background too: alerts for missing stops, near stop or target, big losses, news before a trade, crowded funding, and a habit check after trades close.", 12f, Theme.MUTED).apply { setPadding(0, dp(6), 0, dp(10)) }
        c.addView(out)
        fun run(wait: String, job: () -> String) {
            out.setTextColor(Theme.TEXT); out.text = wait
            Thread {
                val r = try { job() } catch (e: Exception) { "Failed: " + (e.message ?: e.javaClass.simpleName) }
                runOnUiThread { out.text = r; Motion.enter(out) }
            }.start()
        }
        val sym = TradingDesk.followSymbols(this).firstOrNull { FxData.pairOf(it) == null } ?: "BTCUSDT"
        c.addView(row(
            pill("Positions", true) { run("Reading your positions…") { TradingDesk.positionsText(this) } },
            pill("Sync & habits", false) { run("Syncing your Bybit trades and reviewing habits…") { TradingDesk.habits(this).text() } },
            pill("Funding " + sym.removeSuffix("USDT"), false) { run("Reading funding and open interest…") { TradingDesk.derivatives(this, sym)?.text ?: "No derivatives data right now." } }
        ))
        return c
    }

    private fun askText(title: String, hint: String, secondHint: String? = null, done: (String, String) -> Unit) {
        val a = EditText(this).apply { this.hint = hint; setSingleLine(true) }
        val b = secondHint?.let { h -> EditText(this).apply { this.hint = h; setSingleLine(true) } }
        val w = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(a); b?.let { addView(it) } }
        AlertDialog.Builder(this).setTitle(title).setView(w)
            .setPositiveButton("Add") { _, _ -> done(a.text.toString(), b?.text?.toString().orEmpty()) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Public wallets and memecoins, read-only. No keys, no signing, no transactions. */
    private fun chainCard(): LinearLayout {
        val c = card()
        c.addView(label("MEMECOINS & WALLETS (read-only)", 12f, Theme.MUTED, true))
        val watching = ChainDesk.memes(this).joinToString(", ") { it.symbol }.ifBlank { "no memecoins" } + " · " +
            ChainDesk.wallets(this).let { if (it.isEmpty()) "no wallets" else it.joinToString(", ") { w -> w.label } }
        c.addView(label("Watching: $watching", 13f, Theme.TEXT, true).apply { setPadding(0, dp(6), 0, dp(4)) })
        c.addView(label("Public addresses and market data only. Never paste a private key or seed phrase anywhere; Orion refuses them.", 11f, Theme.MUTED))
        val out = label("", 12f, Theme.TEXT).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        fun run(wait: String, job: () -> String) {
            out.visibility = View.VISIBLE; out.text = wait
            Thread {
                val r = try { job() } catch (e: Exception) { "Failed: " + (e.message ?: e.javaClass.simpleName) }
                runOnUiThread { out.text = r; Motion.enter(out) }
            }.start()
        }
        c.addView(row(
            pill("Add memecoin", false) { askText("Watch a memecoin", "Name, symbol or contract address") { q, _ -> run("Searching…") { ChainDesk.addMeme(this, q).also { runOnUiThread { render() } } } } },
            pill("Add wallet", false) { askText("Watch a wallet", "Public address (Solana or 0x…)", "Label (optional)") { a, l -> run("Checking…") { ChainDesk.addWallet(this, a, l).also { runOnUiThread { render() } } } } },
            pill("Check now", true) { run("Reading the chain…") { ChainDesk.overview(this) } }
        ).apply { setPadding(0, dp(10), 0, 0) })
        c.addView(out)
        return c
    }

    private fun openWithFade(i: Intent) {
        startActivity(i)
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** Pick a strategy, symbol and timeframe, then simulate it on history or ask for a forecast. */
    private fun simulateCard(): LinearLayout {
        val c = card()
        c.addView(label("SIMULATE & FORECAST", 12f, Theme.MUTED, true))
        val list = TradingDesk.strategies(this)
        if (list.isEmpty()) {
            c.addView(label("Accept a strategy in your playbook first, then simulate it on real history and see how it would have done.", 13f, Theme.TEXT).apply { setPadding(0, dp(6), 0, 0) })
            return c
        }
        if (list.none { it.name == simStrategy }) simStrategy = list.first().name
        val st = list.first { it.name == simStrategy }
        val symbols = st.instruments.ifEmpty { TradingDesk.followSymbols(this) }.ifEmpty { listOf("BTCUSDT") }
        if (simSymbol !in symbols) simSymbol = symbols.first()
        c.addView(label("Strategy", 11f, Theme.MUTED).apply { setPadding(0, dp(8), 0, dp(4)) })
        c.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false
            addView(row(*list.map { x -> pill(x.name, x.name == simStrategy) { simStrategy = x.name; render() } }.toTypedArray())) })
        c.addView(label("Symbol", 11f, Theme.MUTED).apply { setPadding(0, dp(8), 0, dp(4)) })
        c.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false
            addView(row(*symbols.map { x -> pill(x.removeSuffix("USDT"), x == simSymbol) { simSymbol = x; render() } }.toTypedArray())) })
        c.addView(label("Timeframe", 11f, Theme.MUTED).apply { setPadding(0, dp(8), 0, dp(4)) })
        c.addView(row(*listOf("15m", "1h", "4h", "1d").map { x -> pill(x, x == simTf) { simTf = x; render() } }.toTypedArray()))
        val curve = EquityCurveView(this).apply { visibility = View.GONE }
        val out = label("", 12f, Theme.TEXT).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        fun run(job: () -> Pair<String, List<Double>>) {
            out.visibility = View.VISIBLE; out.text = "Working on it…"; curve.visibility = View.GONE
            Thread {
                val r = try { job() } catch (e: Exception) { "Failed: " + (e.message ?: e.javaClass.simpleName) to emptyList<Double>() }
                runOnUiThread { out.text = r.first; curve.show(r.second); Motion.enter(out) }
            }.start()
        }
        c.addView(row(
            pill("Simulate", true) { run { TradingDesk.backtest(this, simStrategy, simSymbol, simTf).let { it.text to it.curve } } },
            pill("Forecast", false) { run { TradingDesk.forecast(this, simSymbol) to emptyList() } }
        ).apply { setPadding(0, dp(12), 0, 0) })
        c.addView(curve, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        c.addView(out)
        c.addView(label("Simulations use past candles with trading costs; they show how the rules behaved, not what will happen.", 11f, Theme.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        return c
    }

    /** Economic calendar conditions per currency and headline tone, for forex and crypto. */
    private fun conditionsCard(): LinearLayout {
        val c = card()
        c.addView(label("MARKET CONDITIONS", 12f, Theme.MUTED, true))
        val out = label("Tap Refresh to read the Forex Factory calendar and the latest headlines for your playbook pairs.", 12f, Theme.TEXT).apply { setPadding(0, dp(8), 0, dp(8)) }
        c.addView(out)
        c.addView(row(pill("Refresh", true) {
            out.text = "Reading the calendar and headlines…"
            Thread {
                val t = try { TradingDesk.conditions(this, null) } catch (e: Exception) { "Could not read conditions: " + (e.message ?: e.javaClass.simpleName) }
                runOnUiThread { out.text = t; Motion.enter(out) }
            }.start()
        }))
        return c
    }

    private fun playbookCard(): LinearLayout {
        val c = card()
        c.addView(label("PLAYBOOK", 12f, Theme.MUTED, true))
        val pend = TradingDesk.pending(this)
        pend.forEach { st ->
            c.addView(label("Proposed: " + st.name, 15f, Theme.CYAN, true).apply { setPadding(0, dp(10), 0, 0) })
            c.addView(label(describe(st), 12f, Theme.MUTED))
            c.addView(row(
                pill("Accept", true) { TradingDesk.accept(this, st); render() },
                pill("Dismiss", false) { TradingDesk.dismissPending(this, st.name); render() }
            ).apply { setPadding(0, dp(6), 0, 0) })
        }
        val list = TradingDesk.strategies(this)
        if (list.isEmpty() && pend.isEmpty()) c.addView(label("Empty. Open the workspace and upload a chart or describe how you trade; I will propose rules for you to accept.", 13f, Theme.TEXT).apply { setPadding(0, dp(6), 0, 0) })
        list.forEach { st ->
            c.addView(label(st.name, 16f, Theme.TEXT, true).apply { setPadding(0, dp(12), 0, 0) })
            c.addView(label(describe(st), 12f, Theme.MUTED))
            c.addView(label("Results: " + TradingDesk.evidenceFor(this, st.name), 12f, Theme.TEXT))
            c.addView(row(pill("Remove", false) { TradingDesk.deleteStrategy(this, st.name); render() }).apply { setPadding(0, dp(6), 0, 0) })
        }
        val out = label("", 12f, Theme.TEXT).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        c.addView(row(pill("Match now", list.isNotEmpty()) {
            out.visibility = View.VISIBLE; out.text = "Scanning your playbook pairs…"
            Thread {
                val r = try { TradingDesk.scan(this, TradingDesk.followSymbols(this)) } catch (e: Exception) { "Scan failed: " + (e.message ?: e.javaClass.simpleName) }
                runOnUiThread { out.text = r }
            }.start()
        }).apply { setPadding(0, dp(12), 0, 0) })
        c.addView(out)
        return c
    }

    private fun describe(st: StrategySpec) =
        st.instruments.ifEmpty { listOf("any instrument") }.joinToString(", ") + " · " + st.timeframes.ifEmpty { listOf("any timeframe") }.joinToString(", ") +
            " · " + st.setupTypes.ifEmpty { listOf("any setup") }.joinToString(", ") + " · min R:R " + st.minRewardToRisk + (if (st.notes.isNotBlank()) "\n" + st.notes else "")

    private fun deskCard(): LinearLayout {
        val c = card()
        c.addView(label("AUTOMATION & SAFETY", 12f, Theme.MUTED, true))
        c.addView(Switch(this).apply {
            text = "Desk automation (playbook matches, news heads-ups, morning brief)"; setTextColor(Theme.TEXT)
            isChecked = Prefs.deskAuto(this@TradingActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setDeskAuto(this@TradingActivity, on) }
        })
        c.addView(label("Runs inside Market watch below. Alerts only; it never places an order by itself.", 11f, Theme.MUTED).apply { setPadding(0, dp(4), 0, dp(8)) })
        c.addView(Switch(this).apply {
            text = "Kill switch: block every new order"; setTextColor(Theme.TEXT)
            isChecked = TradingDesk.killSwitch(this@TradingActivity)
            setOnCheckedChangeListener { _, on -> TradingDesk.setKill(this@TradingActivity, on) }
        })
        val info = label("Loading news and sessions…", 12f, Theme.MUTED).apply { setPadding(0, dp(10), 0, 0) }
        c.addView(info)
        Thread {
            val t = try {
                val now = System.currentTimeMillis()
                val hour = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).hour
                MarketSessions.describe(hour) + "\n" + MarketCalendar.text(MarketCalendar.upcoming(TradingDesk.events(now), now, 24, MarketCalendar.Impact.HIGH).take(4), now)
            } catch (_: Exception) { "News calendar unavailable right now." }
            runOnUiThread { info.text = t }
        }.start()
        return c
    }

    private fun derivCard(): LinearLayout {
        val c = card()
        c.addView(label("DERIV (forex and gold)", 12f, Theme.MUTED, true))
        c.addView(label(if (DerivDesk.hasToken(this)) "Token saved" else "Not connected", 16f, Theme.TEXT, true).apply { setPadding(0, dp(4), 0, dp(4)) })
        c.addView(label("Start with a DEMO account token (needs trade scope, never withdraw). Every trade is a defined-risk multiplier with a stop loss and target, passes the risk gate and asks you first. Not yet verified on a live account.", 11f, Theme.MUTED))
        val out = label("", 12f, Theme.TEXT).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        c.addView(row(
            pill("Set token", false) { derivDialog() },
            pill("Check", DerivDesk.hasToken(this)) {
                out.visibility = View.VISIBLE; out.text = "Connecting…"
                Thread { val r = DerivDesk.status(this); runOnUiThread { out.text = r } }.start()
            }
        ).apply { setPadding(0, dp(10), 0, 0) })
        c.addView(out)
        c.addView(Switch(this).apply {
            text = "Allow a REAL Deriv account"; setTextColor(Theme.TEXT)
            isChecked = Prefs.derivRealAllowed(this@TradingActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setDerivRealAllowed(this@TradingActivity, on) }
        })
        return c
    }

    private fun derivDialog() {
        val t = EditText(this).apply { hint = "Deriv API token"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val a = EditText(this).apply { hint = "App ID (optional)"; setSingleLine(true); setText(Prefs.derivAppId(this@TradingActivity)) }
        val w = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(t); addView(a) }
        AlertDialog.Builder(this).setTitle("Deriv API token").setMessage("Create a token at app.deriv.com with Read and Trade scopes on a demo account.").setView(w)
            .setPositiveButton("Save") { _, _ ->
                if (t.text.isNotBlank() && !Prefs.setDerivToken(this, t.text.toString().trim())) {
                    Toast.makeText(this, "Secure storage unavailable; token not saved.", Toast.LENGTH_LONG).show()
                } else { Prefs.setDerivAppId(this, a.text.toString()); render() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun startTradeImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/plain", "application/csv", "application/vnd.ms-excel"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_TRADE_IMPORT)
        } catch (_: Exception) {
            Toast.makeText(this, "No compatible file picker is available.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TRADE_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            } ?: "imported-trades.csv"
            val bytes = ByteArrayOutputStream()
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not open selected file.")
            input.use { stream ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 10 * 1024 * 1024) throw IOException("CSV exceeds the 10 MB safety limit.")
                    bytes.write(buffer, 0, count)
                }
            }
            val csv = String(bytes.toByteArray(), Charsets.UTF_8)
            val result = TradeHistoryStore.importCsv(this, csv, name)
            val report = if (result.error != null) {
                result.error
            } else {
                "Import finished. Added: ${result.imported}. Duplicates ignored: ${result.duplicates}. Rows skipped: ${result.skipped}. Total stored records: ${result.total}.\n\n${TradeHistoryStore.summaryText(this)}"
            }
            AlertDialog.Builder(this).setTitle(if (result.error == null) "Trade history imported" else "Import not completed")
                .setMessage(report).setPositiveButton("OK") { _, _ -> render() }.show()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Import failed")
                .setMessage(e.message ?: "The file could not be read. No successful import is claimed.")
                .setPositiveButton("OK") { _, _ -> render() }.show()
        }
    }

    private fun reviewTradeHistory() {
        val report = TradeHistoryStore.summaryText(this) + "\n\nRecent records:\n" + TradeHistoryStore.recentText(this, 20)
        AlertDialog.Builder(this).setTitle("Imported trade history").setMessage(report)
            .setPositiveButton("CLOSE", null).show()
    }

    private fun keyDialog() {
        val k = EditText(this).apply { hint = "Bybit API key"; setSingleLine(true); setText(Prefs.bybitKey(this@TradingActivity)) }
        val s = EditText(this).apply {
            hint = "Bybit API secret"; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val w = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(k); addView(s) }
        AlertDialog.Builder(this).setTitle("Bybit API (Read + Trade only)")
            .setMessage("Practice keys: testnet.bybit.com. Real keys: bybit.com.")
            .setView(w)
            .setPositiveButton("Save") { _, _ ->
                if (!Prefs.setBybit(this, k.text.toString(), s.text.toString())) {
                    AlertDialog.Builder(this)
                        .setTitle("Secure storage unavailable")
                        .setMessage("Orion could not encrypt and save the Bybit credentials. Check Android Keystore and try again.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "Credentials saved securely.", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("Cancel", null).show()
    }

    companion object {
        private const val REQUEST_TRADE_IMPORT = 9501
    }

    private fun toggleMode() {
        if (Prefs.bybitTestnet(this)) {
            AlertDialog.Builder(this).setTitle("Switch to REAL money?")
                .setMessage("Orders use your real balance. Orion still asks before every order and your limits apply.")
                .setPositiveButton("Use real money") { _, _ -> Prefs.setBybitTestnet(this, false); render() }
                .setNegativeButton("Stay in practice", null).show()
        } else {
            Prefs.setBybitTestnet(this, true); render()
        }
    }
}
