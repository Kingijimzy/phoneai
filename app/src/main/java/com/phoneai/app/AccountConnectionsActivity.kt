package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
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
    private var antigravity: LinuxCommandSession? = null
    private lateinit var antigravityOutput: TextView
    private lateinit var antigravityCode: EditText
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
            text = "Sign in with your Google account through the official Antigravity CLI. Orion will verify the session with a real headless model request."
            textSize = 13f
            setTextColor(Theme.MUTED)
        })
        antigravityOutput = TextView(this).apply {
            text = "Not connected"
            textSize = 12f
            setTextColor(Theme.MUTED)
            setPadding(0, dp(8), 0, dp(8))
            setTextIsSelectable(true)
        }
        root.addView(antigravityOutput)
        antigravityCode = EditText(this).apply {
            hint = "Paste the Antigravity terminal login code if requested"
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            setSingleLine(true)
        }
        root.addView(antigravityCode)
        root.addView(Button(this).apply {
            text = "CONNECT GOOGLE ANTIGRAVITY"
            isAllCaps = false
            setOnClickListener { connectAntigravity() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(Button(this).apply {
            text = "SEND LOGIN CODE"
            isAllCaps = false
            setOnClickListener {
                val value = antigravityCode.text.toString().trim()
                if (value.isNotEmpty()) {
                    runCatching { antigravity?.writeLine(value) }
                        .onSuccess { antigravityCode.setText(""); antigravityOutput.append("\\nCode submitted.") }
                        .onFailure { antigravityOutput.append("\\nCould not send code: ${it.message}") }
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)))
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
                val client = installer.openAppServer(filesDir.absolutePath)
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

    private fun connectAntigravity() {
        antigravityOutput.text = "Installing official Antigravity CLI in Alpine ARM64…"
        worker.execute {
            try {
                val installer = AntigravityRuntimeInstaller(applicationContext)
                installer.install({ line ->
                    runOnUiThread { if (!isFinishing) antigravityOutput.append("\\n" + line.take(220)) }
                }).getOrThrow()
                val session = installer.openLoginSession(filesDir.absolutePath)
                antigravity = session
                runOnUiThread { if (!isFinishing) antigravityOutput.text = "Antigravity login process started. Waiting for its official Google OAuth instructions…" }
                while ((session.isAlive() || session.hasPendingOutput()) && !isFinishing) {
                    val line = session.readLine(1_000L) ?: session.readStderrLine(50L) ?: continue
                    runOnUiThread {
                        if (!isFinishing) {
                            antigravityOutput.append("\\n" + line.take(500))
                            val url = Regex("https://[^\\s]+").find(line)?.value?.trimEnd('.', ')', ']')
                            if (url != null) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    }
                }
                val exit = session.exitCodeOrNull()
                if (exit == 0 && !isFinishing) {
                    runOnUiThread { antigravityOutput.append("\\nLogin command exited. Verifying with a real Antigravity model request…") }
                    val result = installer.runPrompt(
                        filesDir.absolutePath,
                        "Reply with exactly: ORION_ANTIGRAVITY_AUTH_OK"
                    )
                    check(result.contains("ORION_ANTIGRAVITY_AUTH_OK")) {
                        "Antigravity did not return the expected authenticated model response."
                    }
                    val models = runCatching { installer.listModels(filesDir.absolutePath) }.getOrDefault(emptyList())
                    if (models.isNotEmpty()) {
                        Prefs.saveCachedModels(applicationContext, "antigravity", models)
                        val provider = Providers.get("antigravity")
                        val current = Prefs.model(applicationContext, provider, false)
                        if (current !in models) Prefs.setModel(applicationContext, provider, false, models.first())
                    }
                    Prefs.setAccountConnected(applicationContext, "antigravity", true)
                    Prefs.setProvider(applicationContext, false, "antigravity")
                    runOnUiThread {
                        if (!isFinishing) antigravityOutput.text =
                            "Google account authenticated · real model request verified.\\nAvailable models: " +
                                models.take(20).joinToString(", ").ifBlank { "model list unavailable" }
                    }
                } else if (!isFinishing) {
                    runOnUiThread { antigravityOutput.append("\\nLogin process ended with exit code ${exit ?: "unknown"}.") }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (!isFinishing) antigravityOutput.append("\\nConnection failed: ${failure.message ?: failure.javaClass.simpleName}")
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
            val verification = runCatching {
                val client = codex ?: error("Codex app-server session is not running")
                val account = client.readAccount()
                if (account?.isChatGptAccount != true) return@runCatching null
                val models = client.availableModels()
                val selectedModel = if (models.isEmpty()) "" else Prefs.model(applicationContext, Providers.get("codex"), false)
                    .takeIf { it in models } ?: models.firstOrNull().orEmpty()
                if (selectedModel.isNotBlank()) Prefs.setModel(applicationContext, Providers.get("codex"), false, selectedModel)
                val thread = client.startThread(selectedModel.takeIf { it.isNotBlank() })
                val answer = client.runTurnAndAwait(thread, "Reply with exactly: ORION_CODEX_AUTH_OK", selectedModel.takeIf { it.isNotBlank() })
                check(answer.contains("ORION_CODEX_AUTH_OK")) { "Codex account did not pass the model verification challenge." }
                Prefs.setAccountConnected(applicationContext, "codex", true)
                Prefs.setProvider(applicationContext, false, "codex")
                account to models
            }
            runOnUiThread {
                val verified = verification.getOrNull()
                if (verified != null) {
                    polling = false
                    val account = verified.first
                    status.text = "ChatGPT account verified · plan: ${account.planType.ifBlank { "unspecified" }} · ${account.email ?: "email not supplied"}"
                    code.text = "ACCOUNT + MODEL VERIFIED"
                    connectButton.text = "CHECK ACCOUNT AGAIN"
                    connectButton.isEnabled = true
                    connectButton.setOnClickListener { startAccountPolling() }
                } else if (verification.isFailure) {
                    status.text = "Codex verification: ${verification.exceptionOrNull()?.message ?: "not ready"}"
                    handler.postDelayed({ pollAccount() }, 5_000L)
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
