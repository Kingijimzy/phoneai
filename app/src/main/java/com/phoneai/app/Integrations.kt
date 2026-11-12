package com.phoneai.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/** Telegram bot token is encrypted with Android Keystore and never returned to the agent. */
object TelegramBridge {
    private const val PREFS = "phoneai_telegram_secure"
    private const val TOKEN = "token_ciphertext"
    private const val IV = "token_iv"
    private const val CHAT = "target_chat"
    private const val UPDATE_OFFSET = "last_update_offset"
    private const val KEY_ALIAS = "com.phoneai.telegram.bot-token.v1"
    private val tokenPattern = Regex("^[0-9]{5,}:[A-Za-z0-9_-]{20,}$")
    private val pollLock = Any()
    private val approvalLock = Any()
    private class PendingApproval {
        val latch = CountDownLatch(1)
        val approved = AtomicBoolean(false)
    }
    @Volatile private var pendingApproval: PendingApproval? = null

    fun isConfigured(context: Context): Boolean =
        decryptToken(context) != null && chatId(context).toLongOrNull()?.let { it > 0L } == true

    fun chatId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAT, "") ?: ""

    fun save(context: Context, token: String, chat: String): String {
        val cleanToken = token.trim()
        val cleanChat = chat.trim()
        if (!tokenPattern.matches(cleanToken)) return "Invalid BotFather token format. Token was not saved."
        val ownerId = cleanChat.toLongOrNull()
        if (ownerId == null || ownerId <= 0L) return "Enter your positive numeric private Telegram chat ID. Group chats and channel usernames are not accepted for owner control."
        return try {
            val identity = request(cleanToken, "getMe", emptyMap())
            val bot = identity.optJSONObject("result")?.optString("username").orEmpty()
            request(cleanToken, "getChat", mapOf("chat_id" to cleanChat))

            // Discard messages which arrived before setup. Old chat text must never become
            // a new Orion command just because the owner connected the bot later.
            var nextOffset = 0L
            var batches = 0
            var batchSize = 0
            do {
                val result = request(cleanToken, "getUpdates", mapOf(
                    "offset" to nextOffset.toString(),
                    "limit" to "100",
                    "timeout" to "0",
                    "allowed_updates" to JSONArray().put("message").toString()
                ))
                val updates = result.optJSONArray("result") ?: JSONArray()
                batchSize = updates.length()
                for (i in 0 until batchSize) {
                    val update = updates.optJSONObject(i) ?: continue
                    nextOffset = maxOf(nextOffset, update.optLong("update_id", -1L) + 1L)
                }
                batches++
            } while (batchSize == 100 && batches < 20)
            if (batchSize == 100) return "Too many pending Telegram updates to initialise safely. Clear the bot's pending chat updates and try again."

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(cleanToken.toByteArray(Charsets.UTF_8))
            val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(CHAT, cleanChat)
                .putLong(UPDATE_OFFSET, nextOffset)
                .commit()
            if (ok) "Telegram bot @" + bot.ifBlank { "connected" } + " verified and saved securely. Only your configured private chat can control Orion."
            else "Could not save Telegram settings."
        } catch (e: Exception) {
            "Could not verify or save Telegram settings (" + e.javaClass.simpleName + "). Open the bot and send /start, then retry."
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { TelegramListenerService.stop(context) }
    }

    fun status(context: Context): String {
        val token = decryptToken(context) ?: return "Telegram is not configured. Add a BotFather token and private chat ID in PhoneAI settings."
        val target = chatId(context)
        if (target.toLongOrNull()?.let { it > 0L } != true) return "Telegram token is saved, but the private owner chat ID is missing or invalid."
        return try {
            val obj = request(token, "getMe", emptyMap())
            val bot = obj.optJSONObject("result") ?: return "Telegram API did not return bot details."
            val listener = if (TelegramListenerService.isEnabled(context)) "background listener active" else "bot connected; background listener is stopped"
            "Telegram connected: @" + bot.optString("username", "unknown") + "; " + listener + ". Inbound control is restricted to the saved private owner chat."
        } catch (e: Exception) {
            "Telegram check failed (" + e.javaClass.simpleName + "). Token remains stored encrypted."
        }
    }

    /** Polls only the saved private owner chat and dispatches owner text through Orion's shared coordinator. */
    fun checkUpdates(context: Context): String = synchronized(pollLock) {
        val token = decryptToken(context) ?: return@synchronized "error: Telegram is not configured."
        val target = chatId(context)
        val owner = target.toLongOrNull()?.takeIf { it > 0L }
            ?: return@synchronized "error: incoming control requires a positive numeric private owner chat ID."
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val offset = prefs.getLong(UPDATE_OFFSET, 0L)
            val result = request(token, "getUpdates", mapOf(
                "offset" to offset.toString(),
                "limit" to "100",
                "timeout" to "0",
                    "allowed_updates" to JSONArray().put("message").toString()
            ))
            val updates = result.optJSONArray("result") ?: return@synchronized "Telegram returned no update list."
            var newest = offset
            var seen = 0
            var ignored = 0
            for (i in 0 until updates.length()) {
                val update = updates.optJSONObject(i) ?: continue
                val updateId = update.optLong("update_id", -1L)
                if (updateId >= 0L) {
                    newest = maxOf(newest, updateId + 1L)
                    // Checkpoint before side effects to prevent a process restart replaying a command.
                    if (!prefs.edit().putLong(UPDATE_OFFSET, newest).commit()) {
                        return@synchronized "error: Telegram update checkpoint could not be saved; no further update was dispatched."
                    }
                }
                val msg = update.optJSONObject("message") ?: continue
                val chat = msg.optJSONObject("chat") ?: continue
                val from = msg.optJSONObject("from") ?: continue
                val incomingChat = chat.optLong("id", Long.MIN_VALUE)
                val sender = from.optLong("id", Long.MIN_VALUE)
                val fromBot = from.optBoolean("is_bot", true)
                val text = msg.optString("text", "").trim()
                if (incomingChat != owner || sender != owner || fromBot || text.isBlank()) {
                    ignored++
                    continue
                }
                seen++

                val awaiting = synchronized(approvalLock) { pendingApproval }
                if (awaiting != null) {
                    when (text.uppercase()) {
                        "YES", "/YES", "APPROVE" -> {
                            awaiting.approved.set(true)
                            synchronized(approvalLock) { if (pendingApproval === awaiting) pendingApproval = null }
                            awaiting.latch.countDown()
                            send(context, "Confirmation received. Orion is continuing the destructive action you were warned about.")
                        }
                        "NO", "/NO", "CANCEL" -> {
                            awaiting.approved.set(false)
                            synchronized(approvalLock) { if (pendingApproval === awaiting) pendingApproval = null }
                            awaiting.latch.countDown()
                            send(context, "Cancelled. Orion will not delete or wipe the selected data.")
                        }
                        else -> send(context, "A destructive action is waiting for your decision. Reply YES or NO first, then send your next instruction.")
                    }
                    continue
                }

                if (text.equals("/start", true) || text.equals("/help", true)) {
                    send(context, "Orion Telegram control is connected. Send me an instruction in this private chat. I will run it through the same central coordinator as the app and report the result. Delete/wipe operations require your explicit confirmation.")
                    continue
                }
                val service = PhoneAiService.instance
                if (service == null || !OrionMasterControl.isEnabled(context)) {
                    send(context, "Orion received your message, but the Orion service is not running. Start Orion on the phone, then send the instruction again.")
                    continue
                }
                service.executeSharedToolRequest(text, source = "Telegram") { answer ->
                    Thread({
                        val safe = answer.trim().ifBlank { "The task finished without a final response. Check Orion's activity timeline." }
                        send(context, "Orion result:\n" + safe.take(12_000))
                        notifyTaskResult(context, safe)
                    }, "OrionTelegramReply").apply { isDaemon = true; start() }
                }
            }
            "Telegram poll complete: " + seen + " authenticated owner message(s) accepted; " + ignored + " unrelated or unsupported update(s) ignored."
        } catch (e: Exception) {
            "Telegram inbox/control failed (" + e.javaClass.simpleName + "). Check the saved chat ID and bot connection."
        }
    }

    /** The owner must answer YES or NO in the authenticated private chat; timeout means deny. */
    fun askApproval(context: Context, question: String): Boolean {
        if (!isConfigured(context) || !TelegramListenerService.isEnabled(context)) return false
        val pending = PendingApproval()
        synchronized(approvalLock) {
            if (pendingApproval != null) return false
            pendingApproval = pending
        }
        val warning = "⚠️ ORION DESTRUCTIVE ACTION WARNING\n" + question +
            "\n\nReply YES to proceed or NO to cancel. This request expires in 90 seconds."
        notifyProactive(context, "Orion needs your decision", question)
        if (send(context, warning).startsWith("error", ignoreCase = true)) {
            synchronized(approvalLock) { if (pendingApproval === pending) pendingApproval = null }
            return false
        }
        val completed = try { pending.latch.await(90, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        synchronized(approvalLock) { if (pendingApproval === pending) pendingApproval = null }
        if (!completed) send(context, "Approval timed out. Orion cancelled the destructive action; no data was deleted or wiped.")
        return completed && pending.approved.get()
    }

    fun sendProactive(context: Context, message: String): Boolean {
        if (!TelegramListenerService.isEnabled(context) || !isConfigured(context)) return false
        return !send(context, message).startsWith("error", ignoreCase = true)
    }

    fun notifyProactive(context: Context, title: String, message: String): Boolean =
        postLocalNotification(context, title, message)

    /** Deduplicated watcher alert; local notification and Telegram delivery retry independently. */
    fun notifyFinding(context: Context, key: String, title: String, message: String) {
        if (key.isBlank() || message.isBlank()) return
        val cleanKey = key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest((title + "\n" + message).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences("orion_telegram_outreach", Context.MODE_PRIVATE)
        val localKey = "local_finding_" + cleanKey
        if (prefs.getString(localKey, "") != fingerprint && notifyProactive(context, title, message)) {
            prefs.edit().putString(localKey, fingerprint).commit()
        }
        val telegramKey = "telegram_finding_" + cleanKey
        if (TelegramListenerService.isEnabled(context) && isConfigured(context) &&
            prefs.getString(telegramKey, "") != fingerprint &&
            sendProactive(context, title + "\n" + message.take(3200))) {
            prefs.edit().putString(telegramKey, fingerprint).commit()
        }
    }

    fun send(context: Context, message: String): String {
        val token = decryptToken(context) ?: return "error: Telegram is not configured."
        val target = chatId(context)
        if (target.toLongOrNull()?.let { it > 0L } != true) return "error: no private Telegram owner chat is configured."
        val body = message.trim()
        if (body.isEmpty()) return "error: message is empty."
        if (body.length > 16_000) return "error: message is too long (maximum 16000 characters)."
        return try {
            val parts = body.chunked(3500)
            for (part in parts) {
                val result = request(token, "sendMessage", mapOf("chat_id" to target, "text" to part))
                if (!result.optBoolean("ok")) return "error: Telegram rejected the message."
            }
            "Telegram message sent to the saved private owner chat."
        } catch (e: Exception) {
            "error: Telegram send failed (" + e.javaClass.simpleName + ")."
        }
    }

    private fun sendTo(context: Context, token: String, target: String, message: String): Boolean {
        val body = message.trim().take(3500)
        if (body.isEmpty()) return false
        return request(token, "sendMessage", mapOf("chat_id" to target, "text" to body)).optBoolean("ok")
    }

    private fun notifyTaskResult(context: Context, result: String) {
        postLocalNotification(context, "Orion finished your Telegram request", result)
    }

    private fun postLocalNotification(context: Context, title: String, result: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = "orion_telegram_task_results"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channel, "Orion task results and proactive alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 7319, android.content.Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = result.replace(Regex("\\\\s+"), " ").take(220)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title.take(100))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        return try {
            manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun request(token: String, method: String, params: Map<String, String>): JSONObject {
        if (method !in setOf("getMe", "getChat", "sendMessage", "getUpdates")) throw IllegalArgumentException("Unsupported Telegram method.")
        val conn = (URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection)
        conn.connectTimeout = 8000
        conn.readTimeout = if (method == "getUpdates") 12000 else 8000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "PhoneAI-Telegram-Bridge")
        if (params.isNotEmpty()) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val encoded = params.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
            conn.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
        } else {
            conn.requestMethod = "GET"
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(2048)
            var total = 0
            while (total < 20_000) {
                val n = input.read(buf, 0, minOf(buf.size, 20_000 - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toString(Charsets.UTF_8.name())
        } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("Telegram API HTTP $code")
        val obj = JSONObject(raw)
        if (!obj.optBoolean("ok")) throw IllegalStateException("Telegram API rejected the request")
        return obj
    }

    private fun decryptToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString(TOKEN, null) ?: return null
        val iv = prefs.getString(IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
                .takeIf { tokenPattern.matches(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
object IntegrationStatus {
    fun summary(context: Context): String = listOf(
        "GitHub public API: available (read-only; public repositories only)",
        "Telegram Bot API: " + if (TelegramBridge.isConfigured(context)) "connected; owner-only control and proactive replies are available when the listener is running" else "not configured",
        "YouTube: browser search only; official metadata API not connected",
        "Gmail: not connected (secure OAuth still required)",
        "Crypto market data: public Bybit spot ticker (read-only; no API key); FX feeds not connected",
        "Remote MCP servers: " + if (McpConnector.configured(context)) "configured; registered calls use Orion's shared execution policy; delete/wipe actions require owner confirmation" else "none configured",
        "Phone tools and declarative skills: local; skills remain allowlisted and budget-limited"
    ).joinToString("\n")
}
