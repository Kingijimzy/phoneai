package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Where secrets are typed. They go straight to the Android-Keystore-encrypted vault and are never
 * placed in chat, logs, prompts or screenshots sent to an AI. Orion can open this screen, but cannot read what you type.
 */
class ConnectionsActivity : Activity() {
    private lateinit var root: LinearLayout
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = Theme.BG; window.navigationBarColor = Theme.BG
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Theme.BG) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(28), dp(18), dp(26)) }
        scroll.addView(root); setContentView(scroll)
        render(intent.getStringExtra("provider").orEmpty())
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false, top: Int = 6) {
        root.addView(TextView(this).apply { text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    private fun button(t: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = t; isAllCaps = false; setTextColor(Theme.TEXT); setOnClickListener { action() } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun render(preset: String) {
        root.removeAllViews()
        text("ORION / CONNECTIONS", 10f, Theme.MUTED, true, 0)
        text("Connect an account", 24f, Theme.TEXT, true, 4)
        text("Secrets typed here are encrypted on this phone and never shown to the AI or put in chat. Orion uses them only for read-only checks. Normal passwords do not work with Gmail or GitHub any more: use the token types below.", 13f, Theme.MUTED, false, 8)
        val connected = OrionConnectionStore.list(this)
        if (connected.isNotEmpty()) {
            text("CONNECTED", 10f, Theme.MUTED, true, 18)
            connected.forEach { cn ->
                text("${cn.providerId} · ${cn.accountLabel} · " + if (cn.secretAvailable) "secret saved" else "no secret", 14f, Theme.TEXT)
                button("Disconnect ${cn.providerId}") { OrionConnectionStore.disconnect(this, cn.providerId); render("") }
            }
        }
        text("ADD", 10f, Theme.MUTED, true, 18)
        button("GitHub (personal access token)" + if (preset == "github") "  ◀" else "") { form("github") }
        button("Gmail (email + app password)" + if (preset == "gmail") "  ◀" else "") { form("gmail") }
        if (preset == "github" || preset == "gmail") form(preset)
        button("‹ Back") { finish() }
    }

    private fun form(provider: String) {
        val github = provider == "github"
        val label = EditText(this).apply { hint = if (github) "Account name (e.g. your username)" else "Gmail address"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED) }
        val secret = EditText(this).apply { hint = if (github) "GitHub token (github.com > Settings > Developer settings > Tokens)" else "16-character Gmail app password (Google Account > Security > App passwords)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED) }
        AlertDialog.Builder(this).setTitle(if (github) "Connect GitHub" else "Connect Gmail (read-only)")
            .setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0); addView(label); addView(secret) })
            .setPositiveButton("Save & test") { _, _ ->
                val lab = label.text.toString().trim(); val sec = secret.text.toString().replace(" ", "").trim()
                if (!github && !lab.contains("@")) { toast("Enter your full Gmail address."); return@setPositiveButton }
                val ok = if (github) OrionConnectionStore.saveAuthorizedToken(this, "github", lab.ifBlank { "github" }, sec, setOf("repo"))
                else OrionConnectionStore.saveAuthorizedToken(this, "gmail", lab, sec, setOf(ConnectedAccounts.GMAIL_SCOPE))
                secret.setText("")
                if (!ok) { toast("That didn't look like a valid token/app password (12+ characters, no line breaks)."); return@setPositiveButton }
                toast("Saved. Testing…")
                Thread {
                    val r = if (github) ConnectedAccounts.githubRead(applicationContext, "notifications") else ConnectedAccounts.gmailRead(applicationContext, 1)
                    runOnUiThread { AlertDialog.Builder(this).setTitle(if (r.startsWith("error")) "Saved, but the test failed" else "Connected").setMessage(r.take(400)).setPositiveButton("OK") { _, _ -> render("") }.show() }
                }.start()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
