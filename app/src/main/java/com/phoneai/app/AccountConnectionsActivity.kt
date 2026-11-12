package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private lateinit var antigravityConnectButton: Button
    private var polling = false

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun panelBackground() = GradientDrawable().apply {
        setColor(Theme.PANEL)
        cornerRadius = dp(Theme.CORNER_MEDIUM_DP).toFloat()
        setStroke(dp(1), Theme.LINE)
    }

    private fun actionButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Theme.TEXT)
        backgroundTintList = null
        background = GradientDrawable().apply {
            setColor(Theme.PANEL2)
            cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
            setStroke(dp(1), Theme.LINE_STRONG)
        }
        minHeight = dp(50)
        contentDescription = title
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isFocusable = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.985f).scaleY(0.985f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).setDuration(Theme.MOTION_PRESS_MS.toLong()).start()
            }
            false
        }
        setOnClickListener { action() }
    }

    private fun addSection(root: LinearLayout, title: String, content: LinearLayout.() -> Unit) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(16), dp(15), dp(16), dp(16))
            addView(label(title, 15f, Theme.TEXT, true))
            content()
        }
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        section.alpha = 0f
        section.translationY = dp(6).toFloat()
        section.post {
            section.animate().alpha(1f).translationY(0f)
                .setDuration(Theme.MOTION_MEDIUM_MS.toLong()).start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(20), dp(17), dp(28))
            setBackgroundColor(Theme.BG)
        }
        root.addView(label("ORION  /  CONNECTIONS", 10f, Theme.SILVER, true))
        root.addView(label("Account connections", 27f, Theme.TEXT, true).apply {
            setPadding(0, dp(7), 0, dp(5))
        })
        root.addView(label(
            "Use your existing subscriptions through their official clients. Orion routes registered tools through its own permission and execution system.",
            13f, Theme.MUTED
        ).apply { setPadding(0, 0, 0, dp(8)) })

        addSection(root, "OPENAI CODEX") {
            status = label("Not connected", 13f, Theme.MUTED).apply {
                contentDescription = "Codex connection status"
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                setPadding(0, dp(9), 0, dp(8))
            }
            addView(status)
            code = TextView(this@AccountConnectionsActivity).apply {
                textSize = 18f
                typeface = Typeface.MONOSPACE
                setTextColor(Theme.TEXT)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(Theme.PANEL2)
                    cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
                    setStroke(dp(1), Theme.LINE)
                }
                contentDescription = "Codex device authorization code"
            }
            addView(code, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            connectButton = actionButton("Connect with ChatGPT") { connectCodex() }
            addView(connectButton, LinearLayout.LayoutParams(-1, -2))
        }

        addSection(root, "GOOGLE ANTIGRAVITY") {
            addView(label(
                "Sign in through the official Antigravity CLI. Orion verifies the session with a real model request before marking the provider connected.",
                12f, Theme.MUTED
            ).apply { setPadding(0, dp(8), 0, dp(8)) })
            antigravityOutput = TextView(this@AccountConnectionsActivity).apply {
                text = "Not connected"
                textSize = 12f
                setTextColor(Theme.MUTED)
                setPadding(dp(11), dp(10), dp(11), dp(10))
                background = GradientDrawable().apply {
                    setColor(Theme.PANEL2)
                    cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
                    setStroke(dp(1), Theme.LINE)
                }
                setTextIsSelectable(true)
                contentDescription = "Antigravity connection progress"
                accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(antigravityOutput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            antigravityCode = EditText(this@AccountConnectionsActivity).apply {
                hint = "Paste the terminal login code if requested"
                setTextColor(Theme.TEXT)
                setHintTextColor(Theme.MUTED)
                textSize = 14f
                setSingleLine(true)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                background = GradientDrawable().apply {
                    setColor(Theme.PANEL2)
                    cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
                    setStroke(dp(1), Theme.LINE_STRONG)
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                minHeight = dp(50)
                contentDescription = "Antigravity terminal login code"
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        submitAntigravityCode()
                        true
                    } else false
                }
            }
            addView(antigravityCode, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
            antigravityConnectButton = actionButton("Connect Google Antigravity") {
                if (Prefs.accountConnected(this@AccountConnectionsActivity, "antigravity")) verifyAntigravitySession()
                else connectAntigravity()
            }
            addView(antigravityConnectButton, LinearLayout.LayoutParams(-1, -2))
            addView(actionButton("Send login code", ::submitAntigravityCode),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        root.addView(label(
            "Authentication stays with the provider's official sign-in flow. Account session files remain inside the app's private runtime storage; API keys are managed separately through Android Keystore. This screen never collects a password.",
            11f, Theme.MUTED
        ).apply { setPadding(0, dp(16), 0, dp(10)) })
        root.addView(actionButton("Back") { finish() }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(root)
        }
        setContentView(scroll)
        refreshAccountStates()
    }
    private fun refreshAccountStates() {
        if (Prefs.accountConnected(this, "codex")) {
            status.text = "Previously verified ChatGPT account. Verify the current session before use."
            code.text = "SAVED CODEX SESSION"
            connectButton.text = "VERIFY CODEX SESSION"
            connectButton.setOnClickListener { verifyCodexSession() }
        }
        if (Prefs.accountConnected(this, "antigravity")) {
            antigravityOutput.text = "Previously verified Google Antigravity account. Verify the current session before use."
            antigravityConnectButton.text = "VERIFY ANTIGRAVITY SESSION"
        }
    }

    private fun verifyCodexSession() {
        connectButton.isEnabled = false
        status.text = "Reopening the saved Codex session and verifying a real model response…"
        worker.execute {
            var client: CodexAppServerClient? = null
            val result = runCatching {
                val installer = CodexRuntimeInstaller(applicationContext)
                installer.install().getOrThrow()
                client = installer.openAppServer(filesDir.absolutePath)
                codex = client
                check(client!!.initialize()) { "Codex app-server initialization was not acknowledged" }
                val account = client!!.readAccount()
                check(account?.isChatGptAccount == true) { "Saved session is not authenticated as a ChatGPT account." }
                val models = client!!.availableModels()
                val selected = models.firstOrNull { it == Prefs.model(applicationContext, Providers.get("codex"), false) }
                    ?: models.firstOrNull().orEmpty()
                val thread = client!!.startThread(selected.takeIf { it.isNotBlank() })
                val answer = client!!.runTurnAndAwait(thread, "Reply with exactly: ORION_CODEX_AUTH_OK", selected.takeIf { it.isNotBlank() })
                check(answer.contains("ORION_CODEX_AUTH_OK")) { "Codex account did not pass the model verification challenge." }
                if (selected.isNotBlank()) Prefs.setModel(applicationContext, Providers.get("codex"), false, selected)
                Prefs.saveCachedModels(applicationContext, "codex", models)
                Prefs.setAccountConnected(applicationContext, "codex", true)
                Prefs.setProvider(applicationContext, false, "codex")
                account
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                connectButton.isEnabled = true
                result.onSuccess { account ->
                    status.text = "ChatGPT session verified · plan: ${account?.planType?.ifBlank { "unspecified" } ?: "unspecified"}"
                    code.text = "ACCOUNT + MODEL VERIFIED"
                    connectButton.text = "VERIFY AGAIN"
                    connectButton.setOnClickListener { verifyCodexSession() }
                }.onFailure { failure ->
                    status.text = "Saved Codex session could not be verified: ${failure.message ?: "unknown error"}"
                    connectButton.text = "RECONNECT WITH CHATGPT"
                    connectButton.setOnClickListener { connectCodex() }
                }
            }
        }
    }

    private fun verifyAntigravitySession() {
        antigravityConnectButton.isEnabled = false
        antigravityOutput.text = "Verifying saved Antigravity account with a real model request…"
        worker.execute {
            val result = runCatching {
                val installer = AntigravityRuntimeInstaller(applicationContext)
                installer.install().getOrThrow()
                val answer = installer.runPrompt(filesDir.absolutePath, "Reply with exactly: ORION_ANTIGRAVITY_SESSION_OK")
                check(answer.contains("ORION_ANTIGRAVITY_SESSION_OK")) {
                    "Saved Antigravity session did not pass the model verification challenge."
                }
                val models = runCatching { installer.listModels(filesDir.absolutePath) }.getOrDefault(emptyList())
                if (models.isNotEmpty()) Prefs.saveCachedModels(applicationContext, "antigravity", models)
                Prefs.setAccountConnected(applicationContext, "antigravity", true)
                Prefs.setProvider(applicationContext, false, "antigravity")
                models
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                antigravityConnectButton.isEnabled = true
                result.onSuccess { models ->
                    antigravityOutput.text = "Google Antigravity session verified · models: " +
                        models.take(20).joinToString(", ").ifBlank { "model list unavailable" }
                    antigravityConnectButton.text = "VERIFY AGAIN"
                }.onFailure { failure ->
                    antigravityOutput.text = "Saved Antigravity session could not be verified: ${failure.message ?: "unknown error"}"
                    antigravityConnectButton.text = "RECONNECT WITH GOOGLE"
                    antigravityConnectButton.setOnClickListener { connectAntigravity() }
                }
            }
        }
    }

    private fun submitAntigravityCode() {
        val value = antigravityCode.text.toString().trim()
        if (value.isEmpty()) {
            antigravityCode.error = "Enter the code shown by Google first."
            return
        }
        runCatching {
            val session = antigravity ?: error("Start the Antigravity sign-in flow first.")
            session.writeLine(value)
        }.onSuccess {
            antigravityCode.setText("")
            antigravityOutput.append("\nCode submitted.")
        }.onFailure {
            antigravityOutput.append("\nCould not send code: " + (it.message ?: "unknown error"))
        }
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
                codex = client
                check(client.initialize()) { "Codex app-server initialization was not acknowledged" }
                val login = client.beginChatGptLogin()
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
                codex?.close()
                codex = null
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
        antigravityConnectButton.isEnabled = false
        antigravityOutput.text = "Installing official Antigravity CLI in Alpine ARM64…"
        worker.execute {
            try {
                val installer = AntigravityRuntimeInstaller(applicationContext)
                installer.install({ line ->
                    runOnUiThread { if (!isFinishing) antigravityOutput.append("\n" + line.take(220)) }
                }).getOrThrow()
                val session = installer.openLoginSession(filesDir.absolutePath)
                antigravity = session
                runOnUiThread { if (!isFinishing) antigravityOutput.text = "Antigravity login process started. Waiting for its official Google OAuth instructions…" }
                while ((session.isAlive() || session.hasPendingOutput()) && !isFinishing) {
                    val line = session.readLine(1_000L) ?: session.readStderrLine(50L) ?: continue
                    runOnUiThread {
                        if (!isFinishing) {
                            antigravityOutput.append("\n" + line.take(500))
                            val url = Regex("https://[^\\s]+").find(line)?.value?.trimEnd('.', ')', ']')
                            if (url != null) {
                                val normalized = AntigravityOAuthUrlPolicy.normalizeAuthorizationUrl(url)
                                if (normalized == null) {
                                    antigravityOutput.append("\nIgnored an authorization URL that was not a Google OAuth sign-in endpoint.")
                                } else {
                                    if (normalized != url) antigravityOutput.append("\nRemoved Google's unsupported bare userinfo OAuth scope; valid sign-in scopes were preserved.")
                                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized))) }
                                }
                            }
                        }
                    }
                }
                val exit = session.exitCodeOrNull()
                if (exit == 0 && !isFinishing) {
                    runOnUiThread { antigravityOutput.append("\nLogin command exited. Verifying with a real Antigravity model request…") }
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
                            "Google account authenticated · real model request verified.\nAvailable models: " +
                                models.take(20).joinToString(", ").ifBlank { "model list unavailable" }
                    }
                } else if (!isFinishing) {
                    runOnUiThread {
                        antigravityOutput.append("\nLogin process ended with exit code ${exit ?: "unknown"}.")
                        antigravityConnectButton.isEnabled = true
                    }
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        val rootFailure = generateSequence(failure as Throwable?) { it.cause }.last()
                        val detail = buildString {
                            append(failure.javaClass.simpleName)
                            failure.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it.take(240)) }
                            if (rootFailure !== failure) {
                                append(" (root: ").append(rootFailure.javaClass.simpleName)
                                    .append(": ").append(rootFailure.message.orEmpty().take(180)).append(")")
                            }
                        }
                        android.util.Log.e("OrionAntigravity", "Connection failed", failure)
                        antigravityOutput.append("\nConnection failed: $detail")
                        antigravityConnectButton.isEnabled = true
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
        antigravity?.close()
        antigravity = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
