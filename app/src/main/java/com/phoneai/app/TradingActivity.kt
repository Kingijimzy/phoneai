package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
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
        host.addView(label("Control room", 30f, Theme.TEXT, true).apply { setPadding(0, dp(4), 0, dp(14)) })
        host.addView(pill("Trading Coach · upload a chart, find setups, review mistakes", true) {
            startActivity(Intent(this, AgentChatActivity::class.java).putExtra(AgentChatActivity.EXTRA_AGENT, "coach"))
        })
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
