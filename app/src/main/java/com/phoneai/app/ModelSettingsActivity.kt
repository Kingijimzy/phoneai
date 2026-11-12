package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Dedicated graphite model/provider page. Provider secrets remain in ApiKeyVault. */
class ModelSettingsActivity : Activity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var chatProvider: TextView
    private lateinit var visionProvider: TextView
    private lateinit var chatModel: TextView
    private lateinit var visionModel: TextView
    private lateinit var chatStatus: TextView
    private lateinit var visionStatus: TextView
    private lateinit var commandProvider: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var paidSwitch: Switch
    private lateinit var modelRuntime: AccountAwareModelProvider

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Theme.TEXT)
        backgroundTintList = null
        background = GradientDrawable().apply {
            setColor(Theme.PANEL2)
            cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
            setStroke(dp(1), Theme.LINE_STRONG)
        }
        minHeight = dp(48)
        setPadding(dp(12), dp(9), dp(12), dp(9))
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

    private fun card(root: LinearLayout, title: String, body: LinearLayout.() -> Unit) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground()
            setPadding(dp(16), dp(15), dp(16), dp(16))
            addView(label(title, 15f, Theme.TEXT, true))
            body()
        }
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        section.alpha = 0f
        section.translationY = dp(6).toFloat()
        section.post {
            section.animate().alpha(1f).translationY(0f)
                .setDuration(Theme.MOTION_MEDIUM_MS.toLong()).start()
        }
    }

    private fun rowButton(root: LinearLayout, title: String, detail: String, action: () -> Unit) {
        root.addView(label(detail, 12f, Theme.MUTED).apply { setPadding(0, dp(8), 0, dp(6)) })
        root.addView(button(title, action), LinearLayout.LayoutParams(-1, -2))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.loadCustomProviders(applicationContext)
        modelRuntime = AccountAwareModelProvider(applicationContext, Nim(applicationContext), false)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(20), dp(17), dp(28))
            setBackgroundColor(Theme.BG)
        }
        root.addView(label("ORION  /  AI CONFIGURATION", 10f, Theme.SILVER, true))
        root.addView(label("Providers & models", 27f, Theme.TEXT, true).apply {
            setPadding(0, dp(7), 0, dp(5))
        })
        root.addView(label(
            "Chat and screen vision have independent providers and models. Orion uses the selected provider first; fallback remains bounded by your settings and safety policy.",
            13f, Theme.MUTED
        ).apply { setPadding(0, 0, 0, dp(8)) })

        card(root, "CHAT MODEL") {
            chatStatus = label("", 11f, Theme.MUTED).apply { setPadding(0, dp(8), 0, dp(4)) }
            chatProvider = label("", 16f, Theme.TEXT, true)
            chatModel = label("", 12f, Theme.SILVER)
            addView(chatStatus)
            addView(chatProvider)
            addView(chatModel.apply { setPadding(0, dp(3), 0, dp(10)) })
            rowButton(this, "Choose chat provider", "Select the provider that answers normal conversation.") {
                chooseProvider(false)
            }
            rowButton(this, "Refresh and choose chat model", "Fetch the provider's available models, then test the selected model before saving.") {
                chooseModel(false)
            }
            rowButton(this, "Manage chat provider credentials", "API keys are stored through Android Keystore. Account providers use their official sign-in screen.") {
                manageCredentials(false)
            }
        }

        card(root, "SCREEN VISION MODEL") {
            visionStatus = label("", 11f, Theme.MUTED).apply { setPadding(0, dp(8), 0, dp(4)) }
            visionProvider = label("", 16f, Theme.TEXT, true)
            visionModel = label("", 12f, Theme.SILVER)
            addView(visionStatus)
            addView(visionProvider)
            addView(visionModel.apply { setPadding(0, dp(3), 0, dp(10)) })
            rowButton(this, "Choose vision provider", "Independent from chat. Only providers with image-capable models are listed.") {
                chooseProvider(true)
            }
            rowButton(this, "Refresh and choose vision model", "Fetch available models and verify a selection before saving.") {
                chooseModel(true)
            }
            rowButton(this, "Manage vision provider credentials", "Update the encrypted API key or complete the provider's official account sign-in.") {
                manageCredentials(true)
            }
        }

        card(root, "COMMAND / CODING MODEL") {
            commandProvider = label("", 14f, Theme.TEXT, true)
            addView(commandProvider)
            addView(label("Commands and coding tasks can inherit chat provider/model or use an independent selection.", 11f, Theme.MUTED))
            rowButton(this, "Choose command provider", "Select Inherit chat to use normal conversation routing.") { chooseCommandProvider() }
            rowButton(this, "Choose command model", "Select a model specifically for command execution.") { chooseCommandModel() }
        }

        card(root, "ROUTING & COST CONTROL") {
            autoSwitch = Switch(this@ModelSettingsActivity).apply {
                text = "Auto-switch on recoverable provider errors"
                textSize = 13f
                setTextColor(Theme.TEXT)
                thumbTintList = android.content.res.ColorStateList.valueOf(Theme.SILVER)
                trackTintList = android.content.res.ColorStateList.valueOf(Theme.LINE_STRONG)
                isChecked = Prefs.autoSwitch(this@ModelSettingsActivity)
                setOnCheckedChangeListener { _, checked ->
                    Prefs.setAutoSwitch(this@ModelSettingsActivity, checked)
                    refresh()
                }
            }
            addView(autoSwitch)
            addView(label("Uses up to two configured backups for timeouts, rate limits and temporary service failures. Authentication, billing, safety blocks and cancellation stop instead of silently switching.", 11f, Theme.MUTED))
            paidSwitch = Switch(this@ModelSettingsActivity).apply {
                text = "Allow paid or metered providers"
                textSize = 13f
                setTextColor(Theme.TEXT)
                thumbTintList = android.content.res.ColorStateList.valueOf(Theme.SILVER)
                trackTintList = android.content.res.ColorStateList.valueOf(Theme.LINE_STRONG)
                isChecked = Prefs.allowPaidProviders(this@ModelSettingsActivity)
                setOnCheckedChangeListener { _, checked ->
                    Prefs.setAllowPaidProviders(this@ModelSettingsActivity, checked)
                    refresh()
                }
            }
            addView(paidSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(label("When off, providers marked paid or metered are blocked before a request is sent. Enabling this accepts that usage may incur charges.", 11f, Theme.MUTED))
        }

        card(root, "PROVIDER MANAGEMENT") {
            rowButton(this, "Connect Codex or Antigravity", "Complete official account sign-in and verify the real model session.") {
                startActivity(Intent(this@ModelSettingsActivity, AccountConnectionsActivity::class.java))
            }
            rowButton(this, "Sign in with OpenRouter", "Complete OpenRouter's official PKCE sign-in; Orion stores the returned key encrypted.") {
                startOpenRouterSignIn()
            }
            rowButton(this, "Add custom API endpoint", "Add an OpenAI-compatible or Anthropic-compatible provider.") {
                addCustomProvider()
            }
            rowButton(this, "Manage custom endpoints", "Review and remove user-added provider endpoints.") {
                manageCustomProviders()
            }
            rowButton(this, "Provider health and quota", "Inspect readiness, last failures and provider-reported limits.") {
                startActivity(Intent(this@ModelSettingsActivity, OrionCapabilityReportActivity::class.java))
            }
        }

        root.addView(button("Back to settings", ::finish), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(18)
        })
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(root)
        }
        setContentView(scroll)
        refresh()
        if (intent.getBooleanExtra("start_openrouter_signin", false)) {
            root.post { if (!isFinishing && !isDestroyed) startOpenRouterSignIn() }
        }
    }

    private fun providerStatus(provider: Provider): String {
        val configured = when (provider.id) {
            "codex", "antigravity" -> Prefs.accountConnected(this, provider.id)
            "local" -> Prefs.hasKey(this, provider.id)
            else -> provider.noKey || Prefs.hasKey(this, provider.id)
        }
        val paidBlocked = provider.requiresPaidOptIn && !Prefs.allowPaidProviders(this)
        return when {
            provider.id in setOf("codex", "antigravity") && !configured -> "ACCOUNT NOT CONNECTED"
            provider.id == "local" && !configured -> "LOCAL ENDPOINT NOT ENABLED"
            !configured -> "API KEY REQUIRED"
            paidBlocked -> "CONNECTED · PAID/METERED USE BLOCKED"
            provider.id in setOf("codex", "antigravity") -> "ACCOUNT SESSION VERIFIED"
            provider.id == "local" -> "LOCAL ENDPOINT ENABLED · NOT LIVE-TESTED"
            provider.noKey -> "NO-KEY ENDPOINT · NOT LIVE-TESTED"
            else -> "API KEY SAVED · ELIGIBLE TO TRY"
        }
    }

    private fun refresh() {
        val chat = Prefs.provider(this, false)
        val vision = Prefs.provider(this, true)
        chatProvider.text = chat.label
        chatModel.text = "Model: " + Prefs.model(this, chat, false)
        chatStatus.text = providerStatus(chat)
        val command = Prefs.commandProviderId(this)?.let { Providers.find(it) }
        commandProvider.text = if (command == null) "Inherit chat · ${chat.label} / ${Prefs.model(this, chat, false)}"
            else "${command.label} · ${Prefs.commandModelId(this) ?: command.textModel}"
        visionProvider.text = vision.label
        visionModel.text = "Model: " + Prefs.model(this, vision, true)
        visionStatus.text = providerStatus(vision)
    }

    private fun chooseCommandProvider() {
        val options = listOf<Provider?>(null) + Providers.all
        val labels = options.map { p -> if (p == null) "Inherit chat" else p.label + " · " + providerStatus(p) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Command provider").setItems(labels) { _, index ->
            val selected = options[index]
            if (selected == null) { Prefs.clearCommandProvider(this); refresh() }
            else {
                val configured = selected.noKey || Prefs.hasKey(this, selected.id) ||
                    (selected.id == "codex" || selected.id == "antigravity") && Prefs.accountConnected(this, selected.id)
                if (!configured) {
                    if (selected.id == "codex" || selected.id == "antigravity") startActivity(Intent(this, AccountConnectionsActivity::class.java))
                    else editApiKey(selected, false)
                    return@setItems
                }
                val model = Prefs.commandModelId(this) ?: Prefs.cachedModels(this, selected.id).firstOrNull() ?: selected.textModel
                Prefs.setCommandProvider(this, selected.id, model)
                refresh()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseCommandModel() {
        val provider = Prefs.commandProviderId(this)?.let { Providers.find(it) } ?: Prefs.provider(this, false)
        if ((provider.id == "codex" || provider.id == "antigravity") && !Prefs.accountConnected(this, provider.id)) {
            startActivity(Intent(this, AccountConnectionsActivity::class.java)); return
        }
        if (!provider.noKey && provider.id != "codex" && provider.id != "antigravity" && !Prefs.hasKey(this, provider.id)) {
            editApiKey(provider, false); return
        }
        val cached = Prefs.cachedModels(this, provider.id)
        val progress = ProgressDialog.show(this, "Loading command models", "Contacting ${provider.label}…", true, false)
        worker.execute {
            val loaded = runCatching { modelRuntime.allModels(provider) }
            runOnUiThread {
                if (isFinishing || isDestroyed) { progress.dismiss(); return@runOnUiThread }
                progress.dismiss()
                val models = loaded.getOrNull().orEmpty().ifEmpty { cached }.ifEmpty { listOf(provider.textModel) }.distinct()
                Prefs.saveCachedModels(this, provider.id, models)
                AlertDialog.Builder(this).setTitle("${provider.label} command model").setItems(models.toTypedArray()) { _, index ->
                    val model = models[index]
                    if (!ProviderBillingPolicy.isAllowed(provider.id, model, provider.requiresPaidOptIn, Prefs.allowPaidProviders(this))) {
                        Toast.makeText(this, "This model is blocked by the paid-provider setting.", Toast.LENGTH_LONG).show(); return@setItems
                    }
                    Prefs.setCommandProvider(this, provider.id, model); refresh()
                }.setNegativeButton("Cancel", null).show()
                if (loaded.isFailure && cached.isNotEmpty()) Toast.makeText(this, "Using cached models; refresh failed.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun chooseProvider(vision: Boolean) {
        val options = Providers.all.filter { !vision || it.hasVision }
        val labels = options.map { provider ->
            val current = if (vision) Prefs.provider(this, true).id == provider.id else Prefs.provider(this, false).id == provider.id
            (if (current) "✓  " else "") + provider.label + " · " + providerStatus(provider)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (vision) "Screen-vision provider" else "Chat provider")
            .setItems(labels) { _, index -> selectProvider(options[index], vision) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectProvider(provider: Provider, vision: Boolean) {
        if (provider.id == "local" && !Prefs.hasKey(this, provider.id)) {
            localProviderDialog(provider)
            return
        }
        if (provider.id == "codex" || provider.id == "antigravity") {
            if (!Prefs.accountConnected(this, provider.id)) {
                Toast.makeText(this, "Connect the account first.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, AccountConnectionsActivity::class.java))
                return
            }
        } else if (!provider.noKey && !Prefs.hasKey(this, provider.id)) {
            editApiKey(provider, vision)
            return
        }
        Prefs.setProvider(this, vision, provider.id)
        refresh()
    }

    private fun manageCredentials(vision: Boolean) {
        val provider = Prefs.provider(this, vision)
        if (provider.id == "codex" || provider.id == "antigravity") {
            startActivity(Intent(this, AccountConnectionsActivity::class.java))
        } else if (provider.id == "local") {
            localProviderDialog(provider)
        } else if (provider.noKey) {
            AlertDialog.Builder(this).setTitle(provider.label)
                .setMessage("This endpoint does not use an API key. Verify that its service is running and reachable before selecting a model.")
                .setPositiveButton("OK", null).show()
        } else if (Prefs.hasKey(this, provider.id)) {
            AlertDialog.Builder(this)
                .setTitle(provider.label + " credentials")
                .setItems(arrayOf("Replace API key", "Remove saved API key")) { _, choice ->
                    if (choice == 0) editApiKey(provider, vision) else removeProviderKey(provider, vision)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            editApiKey(provider, vision)
        }
    }

    private fun removeProviderKey(provider: Provider, vision: Boolean) {
        if (!Prefs.setKey(this, provider.id, "")) {
            Toast.makeText(this, "Could not clear the encrypted API key.", Toast.LENGTH_LONG).show()
            return
        }
        val replacement = Providers.all.firstOrNull {
            it.id != provider.id && (!vision || it.hasVision) &&
                (it.noKey && it.id != "local" || Prefs.hasKey(this, it.id))
        }
        replacement?.let { Prefs.setProvider(this, vision, it.id) }
        refresh()
        Toast.makeText(this, "Saved API key cleared.", Toast.LENGTH_SHORT).show()
    }

    private fun editApiKey(provider: Provider, vision: Boolean) {
        val input = EditText(this).apply {
            hint = "Paste API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.MUTED)
            background = GradientDrawable().apply {
                setColor(Theme.PANEL2)
                cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
                setStroke(dp(1), Theme.LINE_STRONG)
            }
            setPadding(dp(12), dp(11), dp(12), dp(11))
            minHeight = dp(50)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label("Stored encrypted with Android Keystore. The key is never inserted into the model prompt.", 12f, Theme.MUTED))
            addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        AlertDialog.Builder(this)
            .setTitle("Connect " + provider.label)
            .setView(wrap)
            .setPositiveButton("Save securely") { _, _ ->
                val key = input.text.toString().trim()
                if (key.isBlank()) {
                    Toast.makeText(this, "No key entered; nothing changed.", Toast.LENGTH_LONG).show()
                } else if (Prefs.setKey(this, provider.id, key)) {
                    Prefs.setProvider(this, vision, provider.id)
                    refresh()
                    Toast.makeText(this, "Encrypted API key saved. Test a model before relying on this provider.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Could not store the key securely.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseModel(vision: Boolean) {
        val provider = Prefs.provider(this, vision)
        if (provider.id == "local" && !Prefs.hasKey(this, provider.id)) {
            localProviderDialog(provider)
            return
        }
        if (provider.id == "codex" || provider.id == "antigravity") {
            if (!Prefs.accountConnected(this, provider.id)) {
                startActivity(Intent(this, AccountConnectionsActivity::class.java))
                return
            }
        } else if (!provider.noKey && !Prefs.hasKey(this, provider.id)) {
            editApiKey(provider, vision)
            return
        }
        val cached = Prefs.cachedModels(this, provider.id)
        val progress = ProgressDialog.show(
            this,
            if (vision) "Loading vision models" else "Loading chat models",
            "Contacting " + provider.label + "…",
            true,
            false
        )
        worker.execute {
            val loaded = runCatching {
                if (provider.noKey && !Prefs.hasKey(this, provider.id)) {
                    cached.ifEmpty { listOf(Prefs.model(this, provider, vision)) }
                } else {
                    modelRuntime.allModels(provider)
                }
            }
            runOnUiThread {
                if (isFinishing) { progress.dismiss(); return@runOnUiThread }
                progress.dismiss()
                val models = loaded.getOrNull()?.takeIf { it.isNotEmpty() }?.filter { it.isNotBlank() }
                    ?: cached.ifEmpty { listOf(Prefs.model(this, provider, vision)) }
                if (models.isEmpty()) {
                    AlertDialog.Builder(this).setTitle("No models available")
                        .setMessage(loaded.exceptionOrNull()?.message ?: "The provider returned no models.")
                        .setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                if (loaded.isFailure && cached.isNotEmpty()) {
                    Toast.makeText(this, "Using cached model list; refresh failed.", Toast.LENGTH_LONG).show()
                }
                Prefs.saveCachedModels(this, provider.id, models)
                AlertDialog.Builder(this)
                    .setTitle(if (vision) provider.label + " vision models" else provider.label + " chat models")
                    .setItems(models.toTypedArray()) { _, index ->
                        verifyAndSaveModel(provider, models[index], vision)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun verifyAndSaveModel(provider: Provider, model: String, vision: Boolean) {
        if (!ProviderBillingPolicy.isAllowed(provider.id, model, provider.requiresPaidOptIn, Prefs.allowPaidProviders(this))) {
            AlertDialog.Builder(this).setTitle("Paid provider blocked")
                .setMessage("Enable the paid/metered provider setting only if you accept possible charges.")
                .setPositiveButton("OK", null).show()
            return
        }
        if (provider.noKey && !Prefs.hasKey(this, provider.id)) {
            Prefs.setModel(this, provider, vision, model)
            refresh()
            Toast.makeText(this, "Model saved. Availability depends on the configured local endpoint.", Toast.LENGTH_LONG).show()
            return
        }
        val progress = ProgressDialog.show(this, "Testing model", provider.label + " / " + model, true, false)
        worker.execute {
            val result = runCatching { modelRuntime.testModel(provider, model, vision) }
            runOnUiThread {
                if (isFinishing) { progress.dismiss(); return@runOnUiThread }
                progress.dismiss()
                val problem = result.getOrNull().orEmpty()
                if (result.isSuccess && problem.isBlank()) {
                    Prefs.setModel(this, provider, vision, model)
                    refresh()
                    Toast.makeText(this, "Model tested and saved.", Toast.LENGTH_LONG).show()
                } else {
                    val reason = result.exceptionOrNull()?.message ?: problem
                    AlertDialog.Builder(this)
                        .setTitle("Model test did not pass")
                        .setMessage(reason.ifBlank { "The provider returned no usable response." })
                        .setPositiveButton("Save anyway") { _, _ ->
                            Prefs.setModel(this, provider, vision, model)
                            refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }
    }

    private fun localProviderDialog(provider: Provider) {
        val enabled = Prefs.hasKey(this, provider.id)
        AlertDialog.Builder(this)
            .setTitle("Local AI on this phone")
            .setMessage(
                "Local AI runs in Termux and can work without a cloud API key. It is slower and best for plain chat.\n\n" +
                    "Set it up once in Termux:\n" +
                    "1. pkg update && pkg install ollama\n" +
                    "2. ollama serve &\n" +
                    "3. ollama pull llama3.2:3b (or llama3.2:1b for faster responses)\n\n" +
                    "Keep Termux running in the background.\n\nCurrent state: " + if (enabled) "ON" else "OFF"
            )
            .setPositiveButton("Turn on") { _, _ ->
                if (Prefs.setKey(this, provider.id, "on")) {
                    Prefs.setProvider(this, false, provider.id)
                    refresh()
                    Toast.makeText(this, "Local AI selected. Test a model to confirm Termux is reachable.", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Could not save local provider state.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Turn off") { _, _ ->
                Prefs.setKey(this, provider.id, "")
                if (Prefs.provider(this, false).id == provider.id) {
                    Providers.all.firstOrNull { it.id != provider.id && (it.noKey || Prefs.hasKey(this, it.id)) }
                        ?.let { Prefs.setProvider(this, false, it.id) }
                }
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addCustomProvider() {
        val name = EditText(this).apply { hint = "Provider name"; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED) }
        val base = EditText(this).apply { hint = "Base URL (https://...)"; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED) }
        val model = EditText(this).apply { hint = "Default model id"; setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED) }
        val key = EditText(this).apply {
            hint = "API key (leave blank for no-key endpoint)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED)
        }
        val anthropic = CheckBox(this).apply {
            text = "Anthropic-compatible messages endpoint"
            setTextColor(Theme.TEXT)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            listOf(name, base, model, key, anthropic).forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }) }
        }
        AlertDialog.Builder(this).setTitle("Add custom API endpoint").setView(wrap)
            .setPositiveButton("Save provider") { _, _ ->
                val id = Prefs.addCustomProvider(
                    this, name.text.toString(), base.text.toString(), model.text.toString(),
                    anthropic.isChecked, key.text.toString()
                )
                if (id == null) {
                    Toast.makeText(this, "Enter a name and a valid HTTP(S) base URL.", Toast.LENGTH_LONG).show()
                } else {
                    Prefs.setProvider(this, false, id)
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startOpenRouterSignIn() {
        try {
            val verifierBytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(verifierBytes)
            val verifier = android.util.Base64.encodeToString(
                verifierBytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            val challengeBytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            val challenge = android.util.Base64.encodeToString(
                challengeBytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            if (!Prefs.setOpenRouterOAuthVerifier(this, verifier)) {
                Toast.makeText(this, "Secure storage unavailable. Sign-in was not started.", Toast.LENGTH_LONG).show()
                return
            }
            val callback = "phoneai://oauth/openrouter"
            val authUrl = "https://openrouter.ai/auth?callback_url=" +
                android.net.Uri.encode(callback) +
                "&code_challenge=" + android.net.Uri.encode(challenge) +
                "&code_challenge_method=S256"
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(authUrl)))
        } catch (failure: Exception) {
            Prefs.setOpenRouterOAuthVerifier(this, "")
            Toast.makeText(this, "Could not start OpenRouter sign-in: " + (failure.message ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
    }

    private fun manageCustomProviders() {
        Prefs.loadCustomProviders(applicationContext)
        val custom = Providers.custom
        if (custom.isEmpty()) {
            Toast.makeText(this, "No custom providers saved.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Custom endpoints")
            .setItems(custom.map { it.label }.toTypedArray()) { _, index ->
                val provider = custom[index]
                AlertDialog.Builder(this)
                    .setTitle(provider.label)
                    .setMessage(provider.base + "\nModel: " + provider.textModel)
                    .setNegativeButton("Keep", null)
                    .setPositiveButton("Remove") { _, _ ->
                        Prefs.setKey(this, provider.id, "")
                        Prefs.removeCustomProvider(this, provider.id)
                        refresh()
                    }
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        modelRuntime.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }
}
