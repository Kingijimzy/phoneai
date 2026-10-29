package com.phoneai.app

import java.net.URI

/**
 * URL boundary for links opened in the chat browser.
 * Only absolute HTTP(S) URLs with a host and without embedded credentials are accepted.
 */
internal object ChatBrowserUrlPolicy {
    fun normalize(raw: String?): String? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isBlank() || candidate.length > 4096) return null
        return runCatching {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "https" && scheme != "http") ||
                uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null ||
                uri.isOpaque
            ) null else uri.toASCIIString()
        }.getOrNull()
    }
}
