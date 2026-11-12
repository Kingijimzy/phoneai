package com.phoneai.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Telegram Bot API bridge.
 *
 * The bot token is encrypted by ApiKeyVault. Only the configured private chat owner is
 * accepted for inbound commands. Incoming text is passed to PhoneAiService's shared
 * coordinator entry point; this class never dispatches shell or device tools directly.
 */
internal object TelegramBridge {
    private const val PREFS = "orion_telegram_bridge_v1"
    private const val CHAT_ID = "owner_chat_id"
    private const val OFFSET = "update_offset"
    private const val TOKEN_ENC = "orion_telegram_bot_token_enc"
    private const val TOKEN_LEGACY = "orion_telegram_bot_token"
    private val pollLock = Any()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun token(context: Context) = ApiKeyVault.read(context.applicationContext, TOKEN_ENC, TOKEN_LEGACY)
    private fun chatId(context: Context) = prefs(context).getString(CHAT_ID, "").orEmpty()

    fun isConfigured(context: Context): Boolean =
        token(context).matches(Regex("^[0-9]{5,15}:[A-Za-z0-9_-]{20,}$")) &&
            chatId(context).toLongOrNull()?.let { it > 0L } == true

    fun save(context: Context, rawToken: String, rawChatId: String): String {
        val cleanToken = rawToken.trim()
        val cleanChat = rawChatId.trim()
        if (!cleanToken.matches(Regex("^[0-9]{5,15}:[A-Za-z0-9_-]{20,}$"))) {
            return "error: BotFather token format is invalid. The token was not saved."
        }
        val id = cleanChat.toLongOrNull()
        if (id == null || id <= 0L) {
            return "error: enter your positive numeric private Telegram chat ID. Group chats are not accepted."
        }
        val identity = api(cleanToken, "getMe", JSONObject())
        if (!identity.optBoolean("ok")) {
            return "error: Telegram could not verify this bot token. Check the token with @BotFather; nothing was saved."
        }
        val bot = identity.optJSONObject("result")?.optString("username").orEmpty()
        val chatCheck = api(cleanToken, "getChat", JSONObject().put("chat_id", id))
        if (!chatCheck.optBoolean("ok")) {
            return "error: Telegram could not access that chat. Open the bot and send /start first, then save again."
        }
        if (!ApiKeyVault.write(context, TOKEN_ENC, TOKEN_LEGACY, cleanToken)) {
            return "error: the bot token could not be encrypted and saved in Android Keystore."
        }
        val saved = prefs(context).edit().putString(CHAT_ID, id.toString()).putLong(OFFSET, 0L).commit()
        if (!saved) return "error: the chat ID could not be saved."
        return "Telegram bot @" + bot.ifBlank { "connected" } + " verified and saved. Only your configured private chat can control Orion."
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(CHAT_ID).remove(OFFSET).commit()
        ApiKeyVault.write(context, TOKEN_ENC, TOKEN_LEGACY, "")
        runCatching { TelegramListenerService.stop(context) }
    }

    fun status(context: Context): String {
        if (!isConfigured(context)) return "Telegram is not configured. Add a verified BotFather token and your private numeric chat ID in Settings → Integrations."
        val result = api(token(context), "getMe", JSONObject())
        if (!result.optBoolean("ok")) return "Telegram bot is configured locally, but Telegram did not verify the connection. Check internet and the bot token."
        val username = result.optJSONObject("result")?.optString("username").orEmpty()
        return "Telegram connected: @" + username.ifBlank { "bot" } + ". Owner chat is configured; inbound commands are restricted to that private chat."
    }

    fun send(context: Context, message: String): String {
        val clean = message.trim()
        if (clean.isBlank()) return "error: Telegram message cannot be blank."
        if (clean.length > 16_000) return "error: Telegram message exceeds the 16000-character safety limit."
        if (!isConfigured(context)) return "error: Telegram is not configured. Set up the bot in Settings → Integrations first."
        val parts = clean.chunked(3900)
        for (part in parts) {
            val result = api(token(context), "sendMessage",
                JSONObject().put("chat_id", chatId(context)).put("text", part).put("disable_web_page_preview", true))
            if (!result.optBoolean("ok")) return "error: Telegram rejected the message. Check the bot's access to your private chat."
        }
        return "Telegram message sent to the configured private chat (" + parts.size + " message part(s))."
    }

    /** Poll once, discard all non-owner updates, and forward accepted text through the shared Agent coordinator. */
    fun checkUpdates(context: Context): String = synchronized(pollLock) {
        if (!isConfigured(context)) return@synchronized "Telegram is not configured."
        val app = context.applicationContext
        val owner = chatId(app).toLongOrNull() ?: return@synchronized "error: saved Telegram chat ID is invalid."
        val offset = prefs(app).getLong(OFFSET, 0L)
        val response = api(token(app), "getUpdates", JSONObject()
            .put("offset", offset)
            .put("timeout", 0)
            .put("allowed_updates", JSONArray().put("message")))
        if (!response.optBoolean("ok")) return@synchronized "error: Telegram update polling failed. The listener will retry."
        val updates = response.optJSONArray("result") ?: JSONArray()
        var accepted = 0
        var ignored = 0
        var latestOffset = offset
        for (i in 0 until updates.length()) {
            val update = updates.optJSONObject(i) ?: continue
            val updateId = update.optLong("update_id", -1L)
            if (updateId >= 0L) latestOffset = maxOf(latestOffset, updateId + 1L)
            val message = update.optJSONObject("message")
            if (message == null) {
                ignored++
                continue
            }
            val chat = message.optJSONObject("chat")
            val from = message.optJSONObject("from")
            val incomingChat = chat?.optLong("id", Long.MIN_VALUE) ?: Long.MIN_VALUE
            val sender = from?.optLong("id", Long.MIN_VALUE) ?: Long.MIN_VALUE
            val isBot = from?.optBoolean("is_bot", false) ?: true
            val text = message.optString("text").trim()
            if (incomingChat != owner || sender != owner || isBot || text.isBlank()) {
                ignored++
                continue
            }
            accepted++
            val service = PhoneAiService.instance
            if (service == null || !OrionMasterControl.isEnabled(app)) {
                send(app, "Orion received your message, but the Orion service is not running. Start Orion on the phone, then send the instruction again.")
                continue
            }
            if (text.equals("/start", true) || text.equals("/help", true)) {
                send(app, "Orion Telegram control is connected. Send me an instruction in this private chat. I will run it through the same central coordinator as the app and report the result. Delete/wipe operations require your explicit confirmation.")
                continue
            }
            service.executeSharedToolRequest(text, source = "Telegram") { result ->
                // Completion runs on the Android main thread; keep network I/O off that thread.
                Thread({
                    val safe = result.trim().ifBlank { "The task finished without a final response. Check Orion's activity timeline." }
                    send(app, "Orion result:\n" + safe.take(12_000))
                    notifyTaskResult(app, safe)
                }, "OrionTelegramReply").apply { isDaemon = true; start() }
            }
        }
        if (latestOffset > offset) prefs(app).edit().putLong(OFFSET, latestOffset).commit()
        "Telegram poll complete: " + accepted + " owner message(s) accepted; " + ignored + " unrelated or unsupported update(s) ignored."
    }

    fun sendProactive(context: Context, message: String): Boolean {
        if (!TelegramListenerService.isEnabled(context) || !isConfigured(context)) return false
        return !send(context, message).startsWith("error", ignoreCase = true)
    }

    fun notifyProactive(context: Context, title: String, message: String) {
        postLocalNotification(context, title, message)
    }

    /** Post a local completion notification without retaining the full task text. */
    private fun notifyTaskResult(context: Context, result: String) {
        postLocalNotification(context, "Orion finished your Telegram request", result)
    }

    private fun postLocalNotification(context: Context, title: String, result: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val channel = "orion_telegram_task_results"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channel, "Orion task results and proactive alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 7319, android.content.Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = result.replace(Regex("\\s+"), " ").take(220)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.take(100))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun api(botToken: String, method: String, body: JSONObject): JSONObject {
        var connection: HttpURLConnection? = null
        return try {
            val getMethod = method == "getMe" || method == "getUpdates"
            val endpoint = "https://api.telegram.org/bot" + botToken + "/" + method
            val requestUrl = if (getMethod && body.length() > 0) {
                val query = body.keys().asSequence().map { key ->
                    java.net.URLEncoder.encode(key, "UTF-8") + "=" +
                        java.net.URLEncoder.encode(body.opt(key).toString(), "UTF-8")
                }.joinToString("&")
                endpoint + "?" + query
            } else endpoint
            connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = if (getMethod) "GET" else "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doInput = true
                if (requestMethod == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (connection.requestMethod == "POST") {
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(128_000) }.orEmpty()
            runCatching { JSONObject(text) }.getOrElse { JSONObject().put("ok", false) }
        } catch (_: Exception) {
            JSONObject().put("ok", false)
        } finally {
            connection?.disconnect()
        }
    }
}
