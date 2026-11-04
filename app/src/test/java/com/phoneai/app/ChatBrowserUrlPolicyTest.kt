package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatBrowserUrlPolicyTest {
    @Test fun acceptsHttpAndHttpsAbsoluteUrls() {
        assertEquals("https://example.com/path?q=orion", ChatBrowserUrlPolicy.normalize(" https://example.com/path?q=orion "))
        assertEquals("http://example.com", ChatBrowserUrlPolicy.normalize("http://example.com"))
    }

    @Test fun rejectsNonWebSchemesAndCredentials() {
        assertNull(ChatBrowserUrlPolicy.normalize("javascript:alert(1)"))
        assertNull(ChatBrowserUrlPolicy.normalize("file:///etc/passwd"))
        assertNull(ChatBrowserUrlPolicy.normalize("intent://example.com"))
        assertNull(ChatBrowserUrlPolicy.normalize("https://user:pass@example.com"))
    }

    @Test fun rejectsMalformedMissingHostAndOversizedValues() {
        assertNull(ChatBrowserUrlPolicy.normalize(""))
        assertNull(ChatBrowserUrlPolicy.normalize("https:///missing-host"))
        assertNull(ChatBrowserUrlPolicy.normalize("not a url"))
        assertNull(ChatBrowserUrlPolicy.normalize("https://" + "a".repeat(4100) + ".com"))
    }
}
