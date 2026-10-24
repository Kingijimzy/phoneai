package com.phoneai.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * Small encrypted history of completed tool routes.
 *
 * The Android Keystore key never leaves the device. Only the sanitized task label and
 * ordered tool names are retained; tool arguments, screen text, results and credentials
 * are deliberately excluded. If encryption/decryption fails, the store fails closed.
 */
object RouteMemoryStore {
    private const val KEY_ALIAS = "orion_route_memory_v1"
    private const val FILE_NAME = "orion_routes.enc"
    private const val MAX_ROUTES = 30
    private const val MAX_LABEL = 220
    private val lock = Any()

    private fun file(c: Context) = File(c.filesDir, FILE_NAME)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        require(payload.isNotEmpty()) { "empty encrypted route store" }
        val ivLength = payload[0].toInt() and 0xff
        require(ivLength in 12..16 && payload.size > 1 + ivLength) { "invalid encrypted route store" }
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun read(c: Context): JSONArray {
        val f = file(c)
        if (!f.exists()) return JSONArray()
        return try {
            val encoded = Base64.decode(f.readText(), Base64.NO_WRAP)
            JSONArray(String(decrypt(encoded), Charsets.UTF_8))
        } catch (_: Exception) {
            // Never treat corrupt/unreadable ciphertext as an empty plaintext history and overwrite it.
            throw IllegalStateException("Encrypted route memory could not be unlocked")
        }
    }

    private fun write(c: Context, routes: JSONArray): Boolean {
        var stream: FileOutputStream? = null
        val atomic = AtomicFile(file(c))
        return try {
            val encrypted = encrypt(routes.toString().toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            stream = atomic.startWrite()
            stream.write(encoded.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) try { atomic.failWrite(stream) } catch (_: Exception) {}
            false
        }
    }

    private fun safeLabel(value: String): String = value
        .replace(Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
        .replace(Regex("(?i)\\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|passwd|secret|authorization|pin|otp|passcode)\\b\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]")
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[URL]")
        .replace(Regex("(?<![A-Za-z0-9])(?:\\+?\\d[\\d ().-]{7,}\\d)(?![A-Za-z0-9])"), "[PHONE]")
        .replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)

    /** Called only after a task checkpoint has been durably marked completed. */
    fun recordCompleted(c: Context, checkpoint: JSONObject): Boolean = synchronized(lock) {
        try {
            val label = safeLabel(checkpoint.optString("command"))
            // Do not build reusable routes for finance, account security, credentials, or login flows.
            if (label.contains(Regex("(?i)\\b(bank|payment|pay|transfer|withdraw|deposit|checkout|trading|trade|wallet|password|passcode|pin|otp|login|log in|sign in|security|credential|recovery phrase)\\b"))) {
                return@synchronized true
            }
            val steps = checkpoint.optJSONArray("steps") ?: JSONArray()
            val tools = JSONArray()
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val tool = step.optString("tool", "")
                val result = step.optString("result", "")
                if (result.startsWith("error", ignoreCase = true)) continue
                if (tool.isNotBlank() && tool !in setOf("remember", "update_memory", "forget", "recall", "type", "copy", "sms", "telegram_send", "mcp_call")) {
                    if (tools.length() == 0 || tools.optString(tools.length() - 1) != tool) tools.put(tool.take(80))
                }
            }
            if (label.isBlank() || tools.length() == 0) return@synchronized true
            val routes = read(c)
            routes.put(JSONObject().put("task", label).put("tools", tools).put("at", System.currentTimeMillis()))
            while (routes.length() > MAX_ROUTES) routes.remove(0)
            write(c, routes)
        } catch (_: Exception) {
            false
        }
    }

    /** Return only relevant, decrypted task labels and tool names as planning hints. */
    fun promptBlock(c: Context, request: String?): String {
        if (request.isNullOrBlank()) return ""
        return synchronized(lock) {
            try {
                val words = request.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
                val routes = read(c)
                val matches = (0 until routes.length()).mapNotNull { routes.optJSONObject(it) }
                    .mapNotNull { route ->
                        val label = route.optString("task")
                        val overlap = words.count { label.lowercase().contains(it) }
                        if (overlap == 0) null else Triple(route, overlap, route.optLong("at"))
                    }
                    .sortedWith(compareByDescending<Triple<JSONObject, Int, Long>> { it.second }.thenByDescending { it.third })
                    .take(3)
                if (matches.isEmpty()) "" else matches.joinToString("\\n") { (route, _, _) ->
                    val names = route.optJSONArray("tools") ?: JSONArray()
                    val sequence = (0 until names.length()).joinToString(" → ") { names.optString(it) }
                    "- Similar completed task: ${route.optString("task").take(MAX_LABEL)}; tool route: ${sequence.take(280)}"
                }
            } catch (_: Exception) {
                ""
            }
        }
    }
}
