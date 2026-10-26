package com.phoneai.app

import android.app.Activity
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
        val code = intent?.data?.getQueryParameter("code")
        if (intent?.data?.scheme != "phoneai" ||
            intent?.data?.host != "oauth" ||
            intent?.data?.path != "/openrouter" ||
            code.isNullOrBlank()
        ) {
            Prefs.setOpenRouterOAuthVerifier(this, "")
            Toast.makeText(this, "OpenRouter sign-in was cancelled or returned no authorization code.", Toast.LENGTH_LONG).show()
            finish()
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
                if (saved) {
                    setResult(RESULT_OK)
                } else {
                    setResult(RESULT_CANCELED)
                }
                finish()
            }
        }.start()
    }
}
