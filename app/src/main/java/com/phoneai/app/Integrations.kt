package com.phoneai.app

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
    private val chatPattern = Regex("^(?:-?[0-9]{1,25}|@[A-Za-z0-9_]{5,32})$")

    fun isConfigured(context: Context): Boolean =
        decryptToken(context) != null && chatId(context).isNotBlank()

    fun chatId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAT, "") ?: ""

    fun save(context: Context, token: String, chat: String): String {
        val cleanToken = token.trim()
        val cleanChat = chat.trim()
        if (!tokenPattern.matches(cleanToken)) return "Invalid BotFather token format. Token was not saved."
        if (!chatPattern.matches(cleanChat)) return "Chat ID must be a numeric chat ID or @channel username."
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(cleanToken.toByteArray(Charsets.UTF_8))
            val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString(CHAT, cleanChat)
                .commit()
            if (ok) "Telegram settings saved securely on this device." else "Could not save Telegram settings."
        } catch (e: Exception) {
            "Could not encrypt Telegram token: ${e.message ?: "keystore error"}"
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun status(context: Context): String {
        val token = decryptToken(context) ?: return "Telegram is not configured. Add a BotFather token and chat ID in PhoneAI settings."
        val target = chatId(context)
        if (target.isBlank()) return "Telegram token is saved, but no target chat is configured."
        return try {
            val obj = request(token, "getMe", emptyMap())
            val bot = obj.optJSONObject("result") ?: return "Telegram API did not return bot details."
            "Telegram connected: @${bot.optString("username", "unknown")} (bot ID ${bot.optString("id", "unknown")}). Saved target: $target. Sending still requires confirmation."
        } catch (e: Exception) {
            "Telegram check failed (${e.javaClass.simpleName}). Token remains stored encrypted."
        }
    }

    /** Polls the saved numeric chat and replies to ordinary text messages. Phone actions are never run from Telegram. */
    fun checkUpdates(context: Context): String {
        val token = decryptToken(context) ?: return "error: Telegram is not configured."
        val target = chatId(context)
        if (!target.matches(Regex("^-?[0-9]{1,25}$"))) return "Incoming checks require a numeric saved chat ID, not a @channel username."
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val offset = prefs.getLong(UPDATE_OFFSET, 0L)
            val result = request(token, "getUpdates", mapOf("offset" to offset.toString(), "limit" to "10", "timeout" to "0"))
            val updates = result.optJSONArray("result") ?: return "Telegram returned no update list."
            var newest = offset
            var seen = 0
            var replied = 0
            val notes = mutableListOf<String>()
            for (i in 0 until updates.length()) {
                val update = updates.optJSONObject(i) ?: continue
                val updateId = update.optLong("update_id", -1L)
                if (updateId >= newest) newest = updateId + 1
                val msg = update.optJSONObject("message") ?: update.optJSONObject("edited_message") ?: continue
                val chat = msg.optJSONObject("chat") ?: continue
                if (chat.optString("id") != target) continue
                val text = msg.optString("text", "").trim()
                if (text.isBlank()) continue
                seen++
                val reply = localTelegramReply(text) ?: try {
                    val history = JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "You are Orion, a concise helpful assistant replying to the user in Telegram. Answer directly and naturally. You may answer questions, but NEVER claim to have executed phone actions, sent other messages, browsed, or changed settings. Never execute device-control commands from Telegram; tell the user to use the Orion app for those actions."))
                        .put(JSONObject().put("role", "user").put("content", text.take(5000)))
                    Nim(context).chat(history, false, 220, 0.45).trim().ifBlank { "I received your message, but my AI provider returned an empty reply. Check Orion AI provider settings." }
                } catch (e: Exception) {
                    "I received your message, but could not generate a reply (" + e.javaClass.simpleName + "). Check Orion AI provider and quota in the app."
                }
                val sent = sendTo(context, token, target, reply.take(3500))
                if (sent) replied++ else notes.add("Telegram rejected a reply for one message.")
            }
            if (newest > offset) prefs.edit().putLong(UPDATE_OFFSET, newest).commit()
            when {
                seen == 0 -> "No new messages from the saved chat."
                replied == seen -> "Processed " + seen + " Telegram message(s) and sent " + replied + " repl" + (if (replied == 1) "y." else "ies.")
                else -> "Received " + seen + " message(s); sent " + replied + " repl" + (if (replied == 1) "y. " else "ies. ") + notes.joinToString(" ")
            }
        } catch (e: Exception) { "Telegram inbox/reply failed (" + e.javaClass.simpleName + "). Check the saved chat ID and bot connection." }
    }

    /** Small commands handled locally without using an AI provider. */
    private fun localTelegramReply(text: String): String? {
        val s = text.trim().lowercase()
        return when (s) {
            "/start", "/start@orionzai_bot", "/help", "/help@orionzai_bot" -> "Hello! I am Orion. Send me a question and I will answer. For phone actions, open the Orion app. Commands: /start, /help, /time, /date."
            "/time", "/time@orionzai_bot" -> "Local phone time: " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
            "/date", "/date@orionzai_bot" -> "Today is " + java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
            else -> null
        }
    }

    private fun sendTo(context: Context, token: String, target: String, message: String): Boolean {
        val body = message.trim().take(3500)
        if (body.isEmpty()) return false
        val result = request(token, "sendMessage", mapOf("chat_id" to target, "text" to body))
        return result.optBoolean("ok")
    }
    /** Sends only to the saved target. Caller must obtain explicit user approval before calling. */
    fun send(context: Context, message: String): String {
        val token = decryptToken(context) ?: return "error: Telegram is not configured."
        val target = chatId(context)
        if (target.isBlank()) return "error: no Telegram target chat is configured."
        val body = message.trim()
        if (body.isEmpty()) return "error: message is empty."
        if (body.length > 3500) return "error: message is too long (maximum 3500 characters)."
        return try {
            val result = request(token, "sendMessage", mapOf("chat_id" to target, "text" to body))
            if (result.optBoolean("ok")) "Telegram message sent to the saved target." else "error: Telegram rejected the message."
        } catch (e: Exception) {
            "error: Telegram send failed (${e.javaClass.simpleName})."
        }
    }

    private fun request(token: String, method: String, params: Map<String, String>): JSONObject {
        if (method !in setOf("getMe", "sendMessage", "getUpdates")) throw IllegalArgumentException("Unsupported Telegram method.")
        val conn = (URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
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
        "Telegram Bot API: " + if (TelegramBridge.isConfigured(context)) "configured locally; verify token to check connectivity" else "not configured",
        "YouTube: browser search only; official metadata API not connected",
        "Gmail: not connected (secure OAuth still required)",
        "Crypto market data: public Bybit spot ticker (read-only; no API key); FX feeds not connected",
        "Remote MCP servers: " + if (McpConnector.configured(context)) "configured; remote tool calls require per-call approval" else "none configured",
        "Phone tools and declarative skills: local; skills remain allowlisted and budget-limited"
    ).joinToString("\n")
}
