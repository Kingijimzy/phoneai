package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
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
    private fun text(v: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = v; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(4), 0, dp(4))
    }
    private fun chip(caption: String, action: () -> Unit) = TextView(this).apply {
        text = caption; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFE4E4E4.toInt()); gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = GradientDrawable().apply { setColor(0xFF242424.toInt()); cornerRadius = dp(11).toFloat(); setStroke(dp(1), 0xFF444444.toInt()) }
        isClickable = true; setOnClickListener { action() }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(0xFF171717.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF343434.toInt()) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090909.toInt(); window.navigationBarColor = 0xFF090909.toInt()
        // Tokens are typed here: block screenshots, screen recording and the recents thumbnail.
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFF090909.toInt()) }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(40), dp(18), dp(30)) }
        scroll.addView(page); setContentView(scroll)
        render()
        intent.getStringExtra("connect")?.let { ServiceCatalog.find(it)?.let { s -> connectDialog(s) } }
    }

    override fun onResume() { super.onResume(); if (::page.isInitialized) render() }

    private fun render() {
        page.removeAllViews()
        page.addView(text("ORION / CAPABILITY BOARD", 10f, 0xFFAAAAAA.toInt(), true))
        page.addView(text("What Orion can reach", 26f, 0xFFF2F2F2.toInt(), true))
        page.addView(text("Tokens are stored encrypted on this phone and are never sent to the AI. Orion uses them only through its own read-only tools.", 12f, 0xFF9A9A9A.toInt()))

        val pack = Switch(this).apply {
            text = "Allow Orion to use connected services"; setTextColor(0xFFE4E4E4.toInt())
            isChecked = AgentPolicy.enabled(this@CapabilityBoardActivity, "connected_services")
            setOnCheckedChangeListener { _, on -> AgentPolicy.setEnabled(this@CapabilityBoardActivity, "connected_services", on) }
        }
        val watch = Switch(this).apply {
            text = "Let Orion watch for new mail and GitHub activity"; setTextColor(0xFFE4E4E4.toInt())
            isChecked = SoulWatch.enabled(this@CapabilityBoardActivity)
            setOnCheckedChangeListener { _, on -> SoulWatch.setEnabled(this@CapabilityBoardActivity, on) }
        }
        val settings = card(); settings.addView(pack); settings.addView(watch)
        settings.addView(text("Watching is event-driven (checks about every 20 minutes while you use the phone), uses no AI, and only notifies when something is new.", 11f, 0xFF8A8A8A.toInt()))
        page.addView(settings)

        page.addView(text("SERVICES", 10f, 0xFFAAAAAA.toInt(), true).apply { setPadding(0, dp(16), 0, 0) })
        ServiceCatalog.all.forEach { s ->
            val conn = OrionConnectionStore.get(this, s.id)
            val c = card()
            c.addView(text(s.title, 16f, 0xFFF2F2F2.toInt(), true))
            val status = when {
                conn?.verified == true && conn.secretAvailable -> "Verified" + (if (s.needsAccount) " as ${conn.accountLabel}" else "")
                conn?.secretAvailable == true -> "Saved · not verified"
                else -> "Not connected"
            }
            c.addView(text(status, 12f, if (conn?.verified == true && conn.secretAvailable) 0xFF8FD18F.toInt() else 0xFF9A9A9A.toInt()))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(chip(if (conn?.secretAvailable == true) "Replace token" else "Connect") { connectDialog(s) })
            if (conn != null) row.addView(chip("Disconnect") {
                OrionConnectionStore.disconnect(this, s.id); Toast.makeText(this, "${s.title} disconnected and its token erased.", Toast.LENGTH_SHORT).show(); render()
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            c.addView(row); page.addView(c)
        }

        page.addView(text("THINGS YOU ASKED FOR THAT ORION COULD NOT DO YET", 10f, 0xFFAAAAAA.toInt(), true).apply { setPadding(0, dp(16), 0, 0) })
        val gaps = CapabilityBoard.open(this)
        val gc = card()
        if (gaps.isEmpty()) gc.addView(text("Nothing waiting. When you ask for something Orion can't do, it is remembered here with a plan.", 12f, 0xFF9A9A9A.toInt()))
        gaps.forEach { g -> gc.addView(text("• ${g.goal}  (${g.provider.ifBlank { "general" }}, asked ${g.count}x, ${g.status})", 13f, 0xFFE4E4E4.toInt())) }
        page.addView(gc)
    }

    private fun connectDialog(s: ServiceCatalog.Service) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        box.addView(text(s.howTo, 12f, 0xFF444444.toInt()))
        val account = EditText(this).apply { hint = "Your Gmail address"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT }
        if (s.needsAccount) box.addView(account)
        val secret = EditText(this).apply {
            hint = s.secretLabel
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(secret)
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
                            "${s.title} was saved securely, but access could not be verified. Check the token/app password and network, then replace it and retry."
                        }
                        AlertDialog.Builder(this)
                            .setTitle(if (verified) "Connection verified" else "Connection not verified")
                            .setMessage(message)
                            .setPositiveButton("OK", null)
                            .show()
                        render()
                    }
                }.start()
            }.show()
    }
}
