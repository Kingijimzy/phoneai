package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Orion's capability board: what is connected, what you asked for that Orion could not do yet, and the secure
 * place to add a token. Secrets typed here go straight into the Keystore vault; they never pass through the AI chat.
 */
class CapabilityBoardActivity : Activity() {
    private lateinit var page: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(v: String, size: Float, color: Int = Theme.MUTED, bold: Boolean = false) = TextView(this).apply {
        text = v; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(4))
    }
    private fun chip(caption: String, action: () -> Unit) = TextView(this).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Theme.TEXT); gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9)); minHeight = dp(48)
        background = GradientDrawable().apply {
            setColor(Theme.PANEL2); cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
            setStroke(dp(1), Theme.LINE_STRONG)
        }
        isClickable = true; isFocusable = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(.985f).scaleY(.985f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
            }
            false
        }
        setOnClickListener { action() }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(15))
        background = GradientDrawable().apply {
            setColor(Theme.PANEL); cornerRadius = dp(Theme.CORNER_MEDIUM_DP).toFloat()
            setStroke(dp(1), Theme.LINE)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun addCard(view: View) {
        page.addView(view)
        view.alpha = 0f
        view.translationY = dp(6).toFloat()
        view.post {
            view.animate().alpha(1f).translationY(0f)
                .setDuration(Theme.MOTION_MEDIUM_MS.toLong()).start()
        }
    }

    private fun field(hintText: String, value: String = "", secret: Boolean = false, email: Boolean = false) =
        EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            background = GradientDrawable().apply {
                setColor(Theme.PANEL2); cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
                setStroke(dp(1), Theme.LINE_STRONG)
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(50)
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }

    private fun horizontalActions(row: LinearLayout): HorizontalScrollView =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Tokens are typed here: block screenshots, screen recording and the recents thumbnail.
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this).apply { setBackgroundColor(Theme.BG); isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(20), dp(17), dp(28)); setBackgroundColor(Theme.BG) }
        scroll.addView(page); setContentView(scroll)
        render()
        intent.getStringExtra("vault_name")?.let { vaultDialog(it, intent.getStringExtra("vault_host").orEmpty(), intent.getStringExtra("vault_style").orEmpty()) }
        intent.getStringExtra("connect")?.let { ServiceCatalog.find(it)?.let { s -> connectDialog(s) } }
    }

    override fun onResume() { super.onResume(); if (::page.isInitialized) render() }

    private fun render() {
        page.removeAllViews()
        page.addView(text("ORION  /  CAPABILITIES", 10f, Theme.SILVER, true))
        page.addView(text("What Orion can reach", 27f, Theme.TEXT, true))
        page.addView(text("Credentials stay encrypted on this phone and are sent only to the service you configure. Orion may summarize returned data in chat. Never paste passwords or API keys into chat.", 12f, Theme.MUTED))

        val pack = Switch(this).apply {
            text = "Allow Orion to use connected services"; setTextColor(Theme.TEXT); minHeight = dp(48); thumbTintList = android.content.res.ColorStateList.valueOf(Theme.SILVER); trackTintList = android.content.res.ColorStateList.valueOf(Theme.LINE_STRONG)
            isChecked = AgentPolicy.enabled(this@CapabilityBoardActivity, "connected_services")
            setOnCheckedChangeListener { _, on -> AgentPolicy.setEnabled(this@CapabilityBoardActivity, "connected_services", on) }
        }
        val watch = Switch(this).apply {
            text = "Let Orion watch for new mail and GitHub activity"; setTextColor(Theme.TEXT); minHeight = dp(48); thumbTintList = android.content.res.ColorStateList.valueOf(Theme.SILVER); trackTintList = android.content.res.ColorStateList.valueOf(Theme.LINE_STRONG)
            isChecked = SoulWatch.enabled(this@CapabilityBoardActivity)
            setOnCheckedChangeListener { _, on -> SoulWatch.setEnabled(this@CapabilityBoardActivity, on) }
        }
        val settings = card(); settings.addView(pack); settings.addView(watch)
        settings.addView(text("Read-only checks run while Orion’s accessibility service is alive. Android battery restrictions may delay them. No AI call is used; notifications are sent only for new results.", 11f, Theme.MUTED))
        addCard(settings)

        page.addView(text("SERVICES", 10f, Theme.SILVER, true).apply { setPadding(0, dp(16), 0, 0) })
        ServiceCatalog.all.forEach { s ->
            val conn = OrionConnectionStore.get(this, s.id)
            val c = card()
            c.addView(text(s.title, 16f, Theme.TEXT, true))
            val status = when {
                conn?.verified == true && conn.secretAvailable -> "Verified" + (if (s.needsAccount) " as ${conn.accountLabel}" else "")
                conn?.secretAvailable == true -> "Saved · not verified"
                else -> "Not connected"
            }
            c.addView(text(status, 12f, if (conn?.verified == true && conn.secretAvailable) Theme.OK else Theme.MUTED))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (conn?.secretAvailable == true && conn.verified != true) {
                row.addView(chip("Verify saved access") { verifyConnection(s) })
            }
            row.addView(chip(if (conn?.secretAvailable == true) "Replace token" else "Connect") { connectDialog(s) })
            if (conn != null) row.addView(chip("Disconnect") {
                OrionConnectionStore.disconnect(this, s.id); Toast.makeText(this, "${s.title} disconnected and its token erased.", Toast.LENGTH_SHORT).show(); render()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            c.addView(horizontalActions(row)); addCard(c)
        }

        page.addView(text("OTHER APPS AND SITES", 10f, Theme.SILVER, true).apply { setPadding(0, dp(16), 0, 0) })
        val vc = card()
        val entries = AppVault.list(this)
        if (entries.isEmpty()) vc.addView(text("Nothing saved. Ask Orion to connect an app, or add one here. Each token is locked to the one site you name.", 12f, Theme.MUTED))
        entries.forEach { e ->
            vc.addView(text(e.name, 15f, Theme.TEXT, true))
            vc.addView(text("${e.host} · " + if (AppVault.hasSecret(this, e)) "token saved" else "no token", 12f, Theme.MUTED))
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(chip("Replace token") { vaultDialog(e.name, e.host, e.style) })
            r.addView(chip("Remove") { AppVault.remove(this, e); render() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            vc.addView(horizontalActions(r))
        }
        vc.addView(chip("Add app or site") { vaultDialog("", "", "") })
        addCard(vc)

        page.addView(text("THINGS YOU ASKED FOR THAT ORION COULD NOT DO YET", 10f, Theme.SILVER, true).apply { setPadding(0, dp(16), 0, 0) })
        val gaps = CapabilityBoard.open(this)
        val gc = card()
        if (gaps.isEmpty()) gc.addView(text("Nothing waiting. When you ask for something Orion can't do, it is remembered here with a plan.", 12f, Theme.MUTED))
        gaps.forEach { g -> gc.addView(text("• ${g.goal}  (${g.provider.ifBlank { "general" }}, asked ${g.count}x, ${g.status})", 13f, Theme.TEXT)) }
        addCard(gc)
    }

    private fun verifyConnection(s: ServiceCatalog.Service) {
        Thread {
            val verified = try {
                when (s.id) {
                    "github" -> GithubConnector.get(applicationContext, "/user").startsWith("GITHUB DATA")
                    "gmail" -> GmailConnector.fetch(applicationContext, "unread", 1).ok
                    else -> false
                }
            } catch (_: Exception) { false }
            OrionConnectionStore.markVerified(applicationContext, s.id, verified)
            if (verified) CapabilityBoard.resolved(applicationContext, s.id)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (verified) {
                    "${s.title} verified. Orion can now use its read-only tools."
                } else {
                    "${s.title} is saved securely, but access could not be verified. Check the token/app password and network, then retry verification or replace the credential."
                }
                AlertDialog.Builder(this)
                    .setTitle(if (verified) "Connection verified" else "Connection not verified")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
                render()
            }
        }.start()
    }

    private fun connectDialog(s: ServiceCatalog.Service) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        box.addView(text(s.howTo, 12f, Theme.MUTED))
        val account = field("Your Gmail address", email = true)
        if (s.needsAccount) box.addView(account)
        val secret = field(s.secretLabel, secret = true)
        box.addView(secret, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        AlertDialog.Builder(this).setTitle("Connect ${s.title}").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Save securely") { _, _ ->
                val label = if (s.needsAccount) account.text.toString().trim() else s.title
                val token = secret.text.toString().trim().replace(" ", "")
                val ok = (!s.needsAccount || label.contains("@")) && OrionConnectionStore.saveAuthorizedToken(this, s.id, label, token, setOf(s.scope))
                if (!ok) {
                    Toast.makeText(this, "Could not save. Check the address and the token.", Toast.LENGTH_LONG).show()
                    render()
                    return@setPositiveButton
                }
                Toast.makeText(this, "Saved securely. Checking access…", Toast.LENGTH_SHORT).show()
                render()
                verifyConnection(s)
            }.show()
    }

    private fun vaultDialog(name0: String, host0: String, style0: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        box.addView(text("Save a token or API key for an app or site. It is locked to the site address you enter and only used for read-only requests. Raw account passwords are not accepted: create an API token or app password in that app.", 12f, Theme.MUTED))
        val name = field("Name (e.g. Notion)", name0)
        val host = field("API address (e.g. api.notion.com)", host0)
        val style = field("bearer  or  header:X-Api-Key", style0.ifBlank { "bearer" })
        val secret = field("Token / API key", secret = true)
        listOf(name, host, style, secret).forEach { box.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        AlertDialog.Builder(this).setTitle("Add app or site").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Save securely") { _, _ ->
                val err = AppVault.save(this, name.text.toString(), host.text.toString(), style.text.toString(), secret.text.toString())
                Toast.makeText(this, err ?: "Saved securely. Tell Orion to continue.", Toast.LENGTH_LONG).show()
                render()
            }.show()
    }
}
