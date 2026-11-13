package com.phoneai.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Visible owner-facing setup for the Telegram bot bridge. Secrets never enter chat or logs. */
class TelegramSettingsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var tokenInput: EditText
    private lateinit var chatInput: EditText
    private lateinit var statusView: TextView
    private lateinit var listenerButton: Button
    private lateinit var sendTestButton: Button

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(3))
        }

    private fun field(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        inputType = type
        textSize = 14f
        setTextColor(Theme.TEXT)
        setHintTextColor(Theme.MUTED)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(Theme.PANEL2)
            cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
            setStroke(dp(1), Theme.LINE)
        }
        minHeight = dp(52)
    }

    private fun action(title: String, onClick: () -> Unit) = Button(this).apply {
        text = title
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        // The token entry surface must not appear in screenshots or the Recents thumbnail.
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(30))
            setBackgroundColor(Theme.BG)
        }
        root.addView(label("ORION  /  TELEGRAM", 10f, Theme.SILVER, true))
        root.addView(label("Telegram bot connection", 26f, Theme.TEXT, true))
        root.addView(label(
            "Connect a bot created with Telegram's BotFather. Orion accepts commands only from the configured private numeric owner chat. The bot token is encrypted with Android Keystore.",
            13f, Theme.MUTED
        ).apply { setPadding(0, dp(6), 0, dp(14)) })

        root.addView(label("BOTFATHER TOKEN", 10f, Theme.SILVER, true))
        tokenInput = field(
            "Paste your BotFather token",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        ).apply {
            isSingleLine = true
            isSaveEnabled = false
            contentDescription = "Telegram BotFather token"
        }
        root.addView(tokenInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

        root.addView(label("PRIVATE OWNER CHAT ID", 10f, Theme.SILVER, true).apply {
            setPadding(0, dp(12), 0, dp(3))
        })
        chatInput = field("Positive numeric Telegram chat ID", InputType.TYPE_CLASS_NUMBER).apply {
            isSingleLine = true
            setText(TelegramBridge.chatId(applicationContext))
            contentDescription = "Telegram private owner chat ID"
        }
        root.addView(chatInput, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

        root.addView(action("SAVE AND VERIFY BOT") { saveAndVerify() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(action("CHECK CONNECTION") { refreshStatus() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        sendTestButton = action("SEND TEST MESSAGE") { sendTestMessage() }
        root.addView(sendTestButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        listenerButton = action("START BACKGROUND LISTENER") { toggleListener() }
        root.addView(listenerButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        root.addView(action("DISCONNECT TELEGRAM") {
            AlertDialog.Builder(this)
                .setTitle("Disconnect Telegram?")
                .setMessage("This clears the encrypted bot token, owner chat ID and listener state from this device.")
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("DISCONNECT") { _, _ -> disconnect() }
                .show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })

        root.addView(label("CONNECTION STATUS", 10f, Theme.SILVER, true).apply {
            setPadding(0, dp(18), 0, dp(5))
        })
        statusView = label("Checking Telegram configuration…", 13f, Theme.MUTED).apply {
            contentDescription = "Telegram connection status"
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Theme.PANEL)
                cornerRadius = dp(Theme.CORNER_SMALL_DP).toFloat()
                setStroke(dp(1), Theme.LINE)
            }
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        root.addView(label(
            "Setup: create a bot with @BotFather, open the bot and send /start, then enter the positive numeric private chat ID for your own account. Start the listener only after the connection check succeeds. Android may restrict background execution; keep its foreground notification enabled.",
            12f, Theme.MUTED
        ).apply { setPadding(0, dp(14), 0, 0) })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
        updateButtons()
        refreshStatus()
    }

    private fun background(action: (Context) -> String, done: (String) -> Unit) {
        worker.execute {
            val result = runCatching { action(applicationContext) }
                .getOrElse { "Telegram operation failed: ${it.message ?: it.javaClass.simpleName}" }
            main.post {
                if (!isFinishing && !isDestroyed) done(result)
            }
        }
    }

    private fun saveAndVerify() {
        val token = tokenInput.text.toString().trim()
        val chat = chatInput.text.toString().trim()
        if (token.isBlank()) {
            tokenInput.error = "Enter the BotFather token."
            return
        }
        if (chat.toLongOrNull()?.let { it > 0L } != true) {
            chatInput.error = "Enter your positive numeric private chat ID."
            return
        }
        statusView.text = "Verifying bot identity and owner chat…"
        background({ context ->
            val saved = TelegramBridge.save(context, token, chat)
            saved + "\n\n" + TelegramBridge.status(context)
        }) { status ->
            tokenInput.text.clear()
            statusView.text = status
            updateButtons()
        }
    }

    private fun refreshStatus() {
        statusView.text = "Checking Telegram connection…"
        background({ TelegramBridge.status(it) }) { status ->
            statusView.text = status
            updateButtons()
        }
    }

    private fun sendTestMessage() {
        statusView.text = "Sending test message to the saved private owner chat…"
        background({ TelegramBridge.send(it, "Orion Telegram test: the private bot connection is working.") }) { result ->
            statusView.text = result
            updateButtons()
        }
    }

    private fun toggleListener() {
        if (TelegramListenerService.isEnabled(applicationContext)) {
            TelegramListenerService.stop(applicationContext)
            statusView.text = "Telegram background listener stopped."
        } else {
            val started = TelegramBridge.isConfigured(applicationContext) && TelegramListenerService.start(applicationContext)
            statusView.text = if (started) {
                "Telegram background listener started. Keep the ongoing Orion notification visible."
            } else {
                "Could not start the listener. Verify the bot token and positive numeric private chat ID first."
            }
        }
        updateButtons()
    }

    private fun disconnect() {
        background({
            TelegramBridge.clear(it)
            "Telegram disconnected. The encrypted token and saved owner chat ID were cleared."
        }) { result ->
            chatInput.setText("")
            tokenInput.text.clear()
            statusView.text = result
            updateButtons()
        }
    }

    private fun updateButtons() {
        val configured = TelegramBridge.isConfigured(applicationContext)
        sendTestButton.isEnabled = configured
        val listening = TelegramListenerService.isEnabled(applicationContext)
        listenerButton.text = if (listening) "STOP BACKGROUND LISTENER" else "START BACKGROUND LISTENER"
        listenerButton.isEnabled = configured || listening
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
    }

    override fun onDestroy() {
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
