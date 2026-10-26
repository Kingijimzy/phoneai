package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent connection metadata for Orion integrations.
 *
 * Secrets are delegated to ApiKeyVault (Android Keystore AES-GCM); this class persists only
 * provider IDs, connection timestamps, and approved scopes. Passwords are deliberately rejected:
 * integrations must use OAuth/device authorization or provider-issued tokens, not saved passwords.
 */
internal object OrionConnectionStore {
    private const val PREFS = "orion_connection_registry_v1"
    private const val REGISTRY = "connections_json"
    private val providerPattern = Regex("^[a-z][a-z0-9_.-]{1,63}$")
    private val scopePattern = Regex("^[a-zA-Z0-9:._/-]{1,120}$")

    data class Connection(
        val providerId: String,
        val accountLabel: String,
        val scopes: Set<String>,
        val connectedAtMillis: Long,
        val secretAvailable: Boolean
    )

    fun list(context: Context): List<Connection> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(REGISTRY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val provider = item.optString("provider")
                    if (!providerPattern.matches(provider)) continue
                    val scopesArray = item.optJSONArray("scopes") ?: JSONArray()
                    val scopes = buildSet {
                        for (j in 0 until scopesArray.length()) {
                            val scope = scopesArray.optString(j)
                            if (scopePattern.matches(scope)) add(scope)
                        }
                    }
                    add(Connection(
                        providerId = provider,
                        accountLabel = item.optString("label", provider).take(100),
                        scopes = scopes,
                        connectedAtMillis = item.optLong("connectedAt", 0L),
                        secretAvailable = ApiKeyVault.read(context, secretKey(provider), legacySecretKey(provider)).isNotBlank()
                    ))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, providerId: String): Connection? =
        list(context).firstOrNull { it.providerId == providerId }

    /**
     * Save a provider-issued token only after the provider's authorization flow has completed.
     * Never pass an account password here. Tokens are encrypted by ApiKeyVault.
     */
    fun saveAuthorizedToken(
        context: Context,
        providerId: String,
        accountLabel: String,
        token: String,
        approvedScopes: Set<String>,
        connectedAtMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!providerPattern.matches(providerId)) return false
        val cleanToken = token.trim()
        if (cleanToken.length < 12 || cleanToken.length > 8192 || cleanToken.contains('\n')) return false
        if (approvedScopes.isEmpty() || approvedScopes.any { !scopePattern.matches(it) }) return false
        val cleanLabel = accountLabel.trim().take(100).ifBlank { providerId }
        val secretKey = secretKey(providerId)
        if (!ApiKeyVault.write(context, secretKey, legacySecretKey(providerId), cleanToken)) return false

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = list(context).filterNot { it.providerId == providerId }
        val item = JSONObject()
            .put("provider", providerId)
            .put("label", cleanLabel)
            .put("scopes", JSONArray(approvedScopes.sorted()))
            .put("connectedAt", connectedAtMillis)
        val array = JSONArray()
        current.forEach { existing ->
            array.put(JSONObject()
                .put("provider", existing.providerId)
                .put("label", existing.accountLabel)
                .put("scopes", JSONArray(existing.scopes.sorted()))
                .put("connectedAt", existing.connectedAtMillis))
        }
        array.put(item)
        return prefs.edit().putString(REGISTRY, array.toString()).commit()
    }

    /** Internal connector-only access. Never expose this return value to chat, logs, or UI. */
    fun tokenForConnector(context: Context, providerId: String): String {
        if (!providerPattern.matches(providerId)) return ""
        return ApiKeyVault.read(context, secretKey(providerId), legacySecretKey(providerId))
    }

    fun hasScope(context: Context, providerId: String, requiredScope: String): Boolean =
        get(context, providerId)?.let { it.secretAvailable && requiredScope in it.scopes } == true

    fun disconnect(context: Context, providerId: String): Boolean {
        if (!providerPattern.matches(providerId)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remaining = list(context).filterNot { it.providerId == providerId }
        val array = JSONArray()
        remaining.forEach { item ->
            array.put(JSONObject()
                .put("provider", item.providerId)
                .put("label", item.accountLabel)
                .put("scopes", JSONArray(item.scopes.sorted()))
                .put("connectedAt", item.connectedAtMillis))
        }
        val metadataSaved = prefs.edit().putString(REGISTRY, array.toString()).commit()
        val secretCleared = ApiKeyVault.write(context, secretKey(providerId), legacySecretKey(providerId), "")
        return metadataSaved && secretCleared
    }

    private fun secretKey(providerId: String) = "orion_connector_${providerId}_token_enc"
    private fun legacySecretKey(providerId: String) = "orion_connector_${providerId}_token"
}
