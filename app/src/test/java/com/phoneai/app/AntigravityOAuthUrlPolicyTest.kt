package com.phoneai.app

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class AntigravityOAuthUrlPolicyTest {
    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    @Test fun removesOnlyInvalidBareUserinfoScope() {
        val scopes = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo"
        ).joinToString(" ")
        val url = "https://accounts.google.com/o/oauth2/auth?client_id=abc&scope=" + enc(scopes) + "&state=xyz"
        val normalized = AntigravityOAuthUrlPolicy.normalizeAuthorizationUrl(url)!!
        val retained = URLDecoder.decode(normalized.substringAfter("scope=").substringBefore("&"), "UTF-8")
        assertFalse(retained.split(Regex("\\s+")).contains("https://www.googleapis.com/auth/userinfo"))
        assertTrue(retained.contains("https://www.googleapis.com/auth/cloud-platform"))
        assertTrue(retained.contains("https://www.googleapis.com/auth/userinfo.email"))
        assertTrue(normalized.contains("client_id=abc"))
        assertTrue(normalized.contains("state=xyz"))
    }

    @Test fun preservesValidScopesAndOtherParameters() {
        val scopes = "openid https://www.googleapis.com/auth/userinfo.email"
        val url = "https://accounts.google.com/o/oauth2/v2/auth?scope=" + enc(scopes) + "&state=keep"
        assertEquals(url, AntigravityOAuthUrlPolicy.normalizeAuthorizationUrl(url))
    }

    @Test fun rejectsNonGoogleOrNonOAuthUrls() {
        assertNull(AntigravityOAuthUrlPolicy.normalizeAuthorizationUrl("https://evil.example/o/oauth2/auth?scope=x"))
        assertNull(AntigravityOAuthUrlPolicy.normalizeAuthorizationUrl("https://accounts.google.com/signin?scope=x"))
        assertNull(AntigravityOAuthUrlPolicy.normalizeAuthorizationUrl("http://accounts.google.com/o/oauth2/auth?scope=x"))
        assertNull(AntigravityOAuthUrlPolicy.normalizeAuthorizationUrl("not a URL"))
    }
}
