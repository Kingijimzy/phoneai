package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppComposePolicyTest {
    @Test fun acceptsInternationalNumbersAndRemovesPlus() {
        assertEquals("2348012345678", WhatsAppComposePolicy.normalizePhone("+2348012345678"))
    }
    @Test fun rejectsLocalOrMalformedNumbers() {
        assertNull(WhatsAppComposePolicy.normalizePhone("08012345678"))
        assertNull(WhatsAppComposePolicy.normalizePhone("+123"))
        assertNull(WhatsAppComposePolicy.normalizePhone("abc"))
    }
    @Test fun validatesPrefilledMessage() {
        assertEquals("Hello", WhatsAppComposePolicy.validateMessage(" Hello "))
        assertNull(WhatsAppComposePolicy.validateMessage(" "))
        assertNull(WhatsAppComposePolicy.validateMessage("x".repeat(1001)))
    }
}