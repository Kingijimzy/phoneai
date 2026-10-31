package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.InputStreamReader
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

/** Trading control room: keys, practice/real mode, limits, playbook and the background market watch. */
class TradingActivity : Activity() {
    private lateinit var host: LinearLayout

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

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }) }
    }

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

    private fun render() {
        host.removeAllViews()
        host.addView(label("Trading", 28f, Theme.TEXT, true))
        host.addView(label("Keys, limits, playbook, watch. Orders need confirmation.", 13f, Theme.MUTED).apply { setPadding(0, dp(4), 0, dp(16)) })

        val modeCard = card()
        modeCard.addView(label("Bybit mode", 12f, Theme.MUTED))
        modeCard.addView(label(Bybit.modeText(this) + if (Bybit.hasKeys(this)) " · keys saved" else " · no keys", 14f, Theme.TEXT).apply { setPadding(0, dp(6), 0, dp(10)) })
        modeCard.addView(row(
            pill("API keys", false) { keyDialog() },
            pill(if (Prefs.bybitTestnet(this)) "Use real money" else "Use testnet", true) { toggleMode() }
        ))
        val readOnlySwitch = Switch(this).apply {
            text = "Read-only (block order placement)"
            setTextColor(Theme.TEXT)
            isChecked = Prefs.tradingReadOnly(this@TradingActivity)
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    Prefs.setTradingReadOnly(this@TradingActivity, true)
                    render()
                } else {
                    AlertDialog.Builder(this@TradingActivity)
                        .setTitle("Enable confirmed order placement?")
                        .setMessage("This permits order proposals to reach confirmation and risk-limit checks. It does not enable automatic trading. Never use an API key with withdrawal permission.")
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

        val book = card()
        book.addView(label("Strategy playbook", 12f, Theme.MUTED))
        val rules = TradingDeskStore.rules(this)
        val cands = TradingDeskStore.candidates(this)
        val enabled = rules.count { it.enabled }
        book.addView(label("$enabled enabled rules · ${rules.size} total · ${cands.size} pending Accept", 13f, Theme.TEXT).apply { setPadding(0, dp(6), 0, dp(8)) })
        book.addView(label("Learn from chart notes and trade results. Candidates never auto-trade; Accept turns them into matcher rules.", 11f, Theme.MUTED).apply { setPadding(0, 0, 0, dp(8)) })
        book.addView(row(
            pill("Review pending", false) { reviewPlaybookCandidates() },
            pill("Match now", true) { runPlaybookMatch() }
        ))
        host.addView(book, LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        val history = card()
        history.addView(label("Imported trade history", 12f, Theme.MUTED))
        history.addView(label(TradeHistoryStore.summaryText(this), 13f, Theme.TEXT).apply { setPadding(0, dp(6), 0, dp(10)) })
        history.addView(label("Import a CSV export from MT5, Bybit or another platform. Local only.", 11f, Theme.MUTED).apply { setPadding(0, 0, 0, dp(8)) })
        history.addView(row(
            pill("Import CSV", false) { startTradeImport() },
            pill("Review stats", true) { reviewTradeHistory() }
        ))
        host.addView(history, LinearLayout.LayoutParams(-1, -2))
        host.addView(gap(12))

        val lim = card()
        lim.addView(label("Safety limits", 12f, Theme.MUTED))
        lim.addView(label("Max order " + Prefs.maxOrderUsd(this) + " USDT · pack trading_execution must be on for agent orders", 13f, Theme.TEXT).apply { setPadding(0, dp(6), 0, 0) })
        host.addView(lim, LinearLayout.LayoutParams(-1, -2))
    }

    private fun startTradeImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_TRADE_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_TRADE_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val name = uri.lastPathSegment ?: "import.csv"
            val text = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: throw IllegalStateException("Could not read file")
            TradeHistoryStore.importCsv(this, text, name)
            Toast.makeText(this, "Import finished", Toast.LENGTH_SHORT).show()
            render()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Import failed")
                .setMessage(e.message ?: "The file could not be read.")
                .setPositiveButton("OK") { _, _ -> render() }.show()
        }
    }

    private fun reviewTradeHistory() {
        val report = TradeHistoryStore.summaryText(this) + "\n\nRecent records:\n" + TradeHistoryStore.recentText(this, 20)
        AlertDialog.Builder(this).setTitle("Imported trade history").setMessage(report)
            .setPositiveButton("CLOSE", null).show()
    }

    private fun reviewPlaybookCandidates() {
        val cands = TradingDeskStore.candidates(this)
        if (cands.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Playbook").setMessage("No pending candidates. Ask Orion to save a strategy note from a chart or trade result.").setPositiveButton("OK", null).show()
            return
        }
        val lines = cands.joinToString("\n\n") { (id, c) ->
            "#$id · ${c.name}\n${c.instrument} ${c.direction} · ${c.conditions.take(4).joinToString(", ")}"
        }
        AlertDialog.Builder(this).setTitle("Pending candidates")
            .setMessage(lines.take(3500))
            .setPositiveButton("Accept first") { _, _ ->
                val id = cands.first().first
                TradingDeskStore.acceptCandidate(this, id, true)
                Toast.makeText(this, "Accepted $id", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNeutralButton("Reject first") { _, _ ->
                TradingDeskStore.rejectCandidate(this, cands.first().first)
                render()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun runPlaybookMatch() {
        Thread {
            val cards = try { TradingDeskStore.matchWatchlist(this) } catch (e: Exception) { emptyList() }
            runOnUiThread {
                val msg = if (cards.isEmpty()) "No setups matched enabled rules on the watchlist."
                else cards.take(8).joinToString("\n\n") { "${it.instrument} ${it.direction} score ${"%.2f".format(it.score)} — ${it.ruleName}\n${it.reasons.take(4).joinToString(", ")}" }
                AlertDialog.Builder(this).setTitle("Matcher").setMessage(msg).setPositiveButton("OK", null).show()
            }
        }.start()
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
                        .setMessage("Orion could not encrypt and save the Bybit credentials.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setPositiveButton
                }
                Toast.makeText(this, "Credentials saved securely.", Toast.LENGTH_SHORT).show()
                render()
            }.setNegativeButton("Cancel", null).show()
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

    companion object {
        private const val REQUEST_TRADE_IMPORT = 9501
    }
}
