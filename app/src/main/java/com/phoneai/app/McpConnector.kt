package com.phoneai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Secure first-stage MCP integration: configure an endpoint and inspect its advertised tools. */
object McpConnector {
    private const val PREFS = "phoneai_mcp_secure"
    private const val URL_KEY = "endpoint"
    private const val TOKEN_KEY = "token_ciphertext"
    private const val IV_KEY = "token_iv"
    private const val ALIAS = "com.phoneai.mcp.bearer.v1"

    fun endpoint(c: Context): String = c.getSharedPreferences(PREFS, 0).getString(URL_KEY, "") ?: ""
    fun configured(c: Context): Boolean = endpoint(c).isNotBlank()

    fun save(c: Context, rawUrl: String, rawToken: String): String {
        val clean = rawUrl.trim()
        val uri = try { java.net.URI(clean) } catch (_: Exception) { return "Invalid MCP endpoint URL." }
        val host = uri.host?.lowercase() ?: return "MCP URL must include a hostname."
        val local = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (uri.userInfo != null || uri.fragment != null ||
            !(uri.scheme == "https" || (uri.scheme == "http" && local))) {
            return "Use HTTPS (HTTP only for localhost). Do not put credentials in the URL."
        }
        if (clean.length > 500 || rawToken.length > 2000) return "MCP settings are too long."
        return try {
            val prefs = c.getSharedPreferences(PREFS, 0)
            val previousEndpoint = prefs.getString(URL_KEY, "") ?: ""
            val edit = prefs.edit().putString(URL_KEY, clean)
            if (rawToken.isBlank()) {
                // Preserve an existing token only when the endpoint is unchanged.
                if (clean != previousEndpoint) edit.remove(TOKEN_KEY).remove(IV_KEY)
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey())
                val encrypted = cipher.doFinal(rawToken.trim().toByteArray(Charsets.UTF_8))
                edit.putString(TOKEN_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            }
            if (edit.commit()) "MCP endpoint saved. Tool discovery is read-only in this initial integration."
            else "Could not save MCP settings."
        } catch (_: Exception) { "Could not securely save MCP settings." }
    }

    fun clear(c: Context) { c.getSharedPreferences(PREFS, 0).edit().clear().commit() }

    fun status(c: Context): String {
        val value = endpoint(c)
        if (value.isBlank()) return "No MCP server configured."
        val host = try { java.net.URI(value).host ?: "unknown" } catch (_: Exception) { "invalid" }
        val auth = if (readToken(c).isNullOrBlank()) "not saved" else "encrypted on device"
        return "MCP endpoint configured for " + host + ". Bearer token: " + auth +
            ". Initial integration discovers tools only; remote execution is not enabled yet."
    }

    @Volatile private var sessionId: String? = null

    fun listTools(c: Context): String {
        if (!configured(c)) return "error: no MCP server configured."
        sessionId = null
        return try {
            val init = post(c, JSONObject()
                .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                .put("params", JSONObject().put("protocolVersion", "2025-03-26")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "PhoneAI").put("version", "1.0"))))
                ?: return "error: MCP initialize returned no response."
            if (init.has("error")) return "error: MCP server rejected initialize."
            post(c, JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
            val response = post(c, JSONObject().put("jsonrpc", "2.0").put("id", 2)
                .put("method", "tools/list").put("params", JSONObject()))
                ?: return "error: MCP server returned no tools/list response."
            val tools = response.optJSONObject("result")?.optJSONArray("tools")
                ?: return "error: MCP response did not contain a tools array."
            if (tools.length() == 0) return "MCP server is reachable but advertises no tools."
            val lines = (0 until minOf(tools.length(), 40)).mapNotNull { i ->
                val item = tools.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("name")
                if (name.isBlank()) null else "- " + name + ": " + item.optString("description", "No description")
            }
            "MCP tools (untrusted descriptions; first 40):\n" + lines.joinToString("\n").take(6000)
        } catch (e: Exception) {
            "MCP discovery failed (" + e.javaClass.simpleName + "). Check URL, authentication, and server protocol."
        }
    }

    private fun post(c: Context, payload: JSONObject): JSONObject? {
        val conn = URL(endpoint(c)).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = false
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.setRequestProperty("User-Agent", "PhoneAI-MCP/1.0")
        readToken(c)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Authorization", "Bearer " + it) }
        sessionId?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        conn.getHeaderField("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var total = 0
            while (total < 80000) {
                val n = input.read(buffer, 0, minOf(buffer.size, 80000 - total))
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            out.toString(Charsets.UTF_8.name())
        } ?: ""
        conn.disconnect()
        if (code !in 200..299 && code != 202) throw IllegalStateException("HTTP " + code)
        if (raw.isBlank()) return null
        val json = if (raw.trimStart().startsWith("{")) raw.trim() else
            raw.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                .firstOrNull { it.startsWith("{") } ?: throw IllegalStateException("unsupported stream response")
        return JSONObject(json)
    }

    private fun readToken(c: Context): String? {
        val prefs = c.getSharedPreferences(PREFS, 0)
        val value = prefs.getString(TOKEN_KEY, null) ?: return null
        val iv = prefs.getString(IV_KEY, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
