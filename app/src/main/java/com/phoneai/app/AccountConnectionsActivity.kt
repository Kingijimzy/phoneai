package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Account-based model connections. Sign-in is performed by each provider's official client. */
class AccountConnectionsActivity : Activity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var codex: CodexAppServerClient? = null
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var connectButton: Button
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Theme.BG)
        }
        root.addView(TextView(this).apply {
            text = "ACCOUNT CONNECTIONS"
            textSize = 20f
            setTextColor(Theme.TEXT)
        })
        root.addView(TextView(this).apply {
            text = "Use your existing subscriptions. Orion will not ask for separate API keys for these account connections."
            textSize = 13f
            setTextColor(Theme.MUTED)
            setPadding(0, dp(8), 0, dp(18))
        })
        root.addView(TextView(this).apply {
            text = "OPENAI CODEX"
            textSize = 15f
            setTextColor(Theme.TEXT)
        })
        status = TextView(this).apply {
            text = "Not connected"
            textSize = 13f
            setTextColor(Theme.MUTED)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)
        code = TextView(this).apply {
            textSize = 20f
            setTextColor(Theme.TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(code)
        connectButton = Button(this).apply {
            text = "CONNECT WITH CHATGPT"
            isAllCaps = false
            setOnClickListener { connectCodex() }
        }
        root.addView(connectButton, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(TextView(this).apply {
            text = "GOOGLE ANTIGRAVITY"
            textSize = 15f
            setTextColor(Theme.TEXT)
            setPadding(0, dp(24), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "The official Antigravity CLI account flow is being integrated separately. Orion will only show this connection as ready after the CLI confirms Google-account authentication and a real model request succeeds."
            textSize = 13f
            setTextColor(Theme.MUTED)
        })
        val back = Button(this).apply {
            text = "BACK"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        root.addView(back, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(24) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun connectCodex() {
        connectButton.isEnabled = false
        status.text = "Preparing Alpine ARM64 runtime and verified Codex app-server…"
        code.text = ""
        worker.execute {
            try {
                val installer = CodexRuntimeInstaller(applicationContext)
                installer.install({ line ->
                    runOnUiThread { if (!isFinishing) status.text = line.take(240) }
                }).getOrThrow()
                val client = installer.openAppServer(filesDir.absolutePath, filesDir.absolutePath)
                check(client.initialize()) { "Codex app-server initialization was not acknowledged" }
                val login = client.beginChatGptLogin()
                codex = client
                runOnUiThread {
                    if (isFinishing) { client.close(); return@runOnUiThread }
                    status.text = "Complete the official ChatGPT sign-in. Return here while Orion checks the account."
                    code.text = login.userCode
                    connectButton.text = "OPEN CHATGPT SIGN-IN"
                    connectButton.isEnabled = true
                    connectButton.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.authUrl)))
                        startAccountPolling()
                    }
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(login.authUrl)))
                    startAccountPolling()
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        status.text = "Codex connection failed: ${failure.message ?: failure.javaClass.simpleName}"
                        connectButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun startAccountPolling() {
        if (polling) return
        polling = true
        pollAccount()
    }

    private fun pollAccount() {
        if (!polling || isFinishing) return
        worker.execute {
            val result = runCatching { codex?.readAccount() }.getOrNull()
            runOnUiThread {
                val account = result
                if (account?.isChatGptAccount == true) {
                    polling = false
                    status.text = "ChatGPT account detected · plan: ${account.planType.ifBlank { "unspecified" }} · ${account.email ?: "email not supplied"}"
                    code.text = if (account.hasSubscriptionEntitlement) "ACCOUNT AUTHENTICATED" else "ACCOUNT AUTHENTICATED · PLAN ENTITLEMENT NOT CONFIRMED"
                    connectButton.text = "CHECK ACCOUNT AGAIN"
                    connectButton.isEnabled = true
                    connectButton.setOnClickListener { startAccountPolling() }
                } else {
                    status.text = "Waiting for ChatGPT device authorization…"
                    handler.postDelayed({ pollAccount() }, 4_000L)
                }
            }
        }
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        codex?.close()
        codex = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
