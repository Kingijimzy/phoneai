package com.phoneai.app

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Normalize only the official Google OAuth URL emitted by Antigravity's CLI. */
internal object AntigravityOAuthUrlPolicy {
    private const val INVALID_SCOPE = "https://www.googleapis.com/auth/userinfo"

    fun normalizeAuthorizationUrl(raw: String): String? = try {
        val uri = URI(raw)
        if (!uri.scheme.equals("https", true) ||
            !uri.host.equals("accounts.google.com", true) ||
            uri.userInfo != null ||
            uri.path !in setOf("/o/oauth2/auth", "/o/oauth2/v2/auth")) return null
        val query = uri.rawQuery ?: return raw
        val parameters = query.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val split = part.split('=', limit = 2)
            val key = decode(split[0])
            if (key != "scope" || split.size < 2) return@mapNotNull part
            val scopes = decode(split[1]).split(Regex("\\s+"))
                .filter { it.isNotBlank() && it != INVALID_SCOPE }
                .distinct()
            if (scopes.isEmpty()) null
            else split[0] + "=" + URLEncoder.encode(scopes.joinToString(" "), StandardCharsets.UTF_8.name())
        }
        if (parameters.isEmpty()) return null
        val base = uri.scheme + "://" + uri.rawAuthority + uri.rawPath
        base + "?" + parameters.joinToString("&") + (uri.rawFragment?.let { "#" + it } ?: "")
    } catch (_: Exception) { null }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}
