package com.phoneai.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles OpenRouter's OAuth PKCE callback. The authorization code is exchanged immediately;
 * the short-lived verifier and resulting API key are never written to logs or exposed in UI.
 */
class OpenRouterOAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent?.data
        val code = callback?.getQueryParameter("code")
        val authError = callback?.getQueryParameter("error")
        if (callback?.scheme != "phoneai" ||
            callback.host != "oauth" ||
            callback.path != "/openrouter"
        ) {
            Prefs.setOpenRouterOAuthVerifier(this, "")
            Toast.makeText(this, "Invalid OpenRouter sign-in callback.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!authError.isNullOrBlank() || code.isNullOrBlank()) {
            Prefs.setOpenRouterOAuthVerifier(this, "")
            Toast.makeText(
                this,
                if (authError.isNullOrBlank()) "OpenRouter sign-in was cancelled." else "OpenRouter authorization was not approved.",
                Toast.LENGTH_LONG
            ).show()
            returnToChat()
            return
        }
        val verifier = Prefs.openRouterOAuthVerifier(this)
        if (verifier.isBlank()) {
            Toast.makeText(this, "OpenRouter sign-in expired. Please try again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Consume the one-time verifier before the exchange so the callback cannot be replayed.
        Prefs.setOpenRouterOAuthVerifier(this, "")
        Thread {
            var connection: HttpURLConnection? = null
            var message = "OpenRouter sign-in failed. Please try again or use an API key."
            var saved = false
            try {
                connection = (URL("https://openrouter.ai/api/v1/auth/keys").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                }
                val body = JSONObject()
                    .put("code", code)
                    .put("code_verifier", verifier)
                    .put("code_challenge_method", "S256")
                    .toString()
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    message = "OpenRouter authorization exchange failed (HTTP $status). You can retry or enter an API key."
                } else {
                    val key = JSONObject(response).optString("key", "").trim()
                    if (key.startsWith("sk-or-") && Prefs.setKey(this, "openrouter", key)) {
                        Prefs.setProvider(this, false, "openrouter")
                        if (Providers.get("openrouter").hasVision) Prefs.setProvider(this, true, "openrouter")
                        saved = true
                        message = "OpenRouter connected securely. Orion saved the returned API key."
                    } else {
                        message = "OpenRouter returned no usable key or secure storage was unavailable. No plaintext key was saved."
                    }
                }
            } catch (_: Exception) {
                message = "Could not finish OpenRouter sign-in. Check your connection and try again."
            } finally {
                connection?.disconnect()
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                setResult(if (saved) RESULT_OK else RESULT_CANCELED)
                returnToChat()
            }
        }.start()
    }

    /** Bring Orion back to the foreground after the browser authorization round-trip. */
    private fun returnToChat() {
        val chat = Intent(this, MainChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(chat)
        finish()
    }
}
