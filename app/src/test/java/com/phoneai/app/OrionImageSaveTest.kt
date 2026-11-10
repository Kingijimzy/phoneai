package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionImageSaveTest {
    @Test fun acceptsOnlyRecognizedImagePayloads() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val webp = "RIFF0000WEBP".toByteArray(Charsets.US_ASCII)
        assertEquals("png", OrionImagePayload.format(png))
        assertEquals("jpg", OrionImagePayload.format(jpeg))
        assertEquals("webp", OrionImagePayload.format(webp))
    }

    @Test fun rejectsHtmlAndTruncatedImageResponses() {
        assertNull(OrionImagePayload.format("<html>rate limited</html>".toByteArray()))
        assertNull(OrionImagePayload.format(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)))
        assertNull(OrionImagePayload.format(ByteArray(12)))
    }

    @Test fun rejectsOversizedPayloads() {
        val oversized = ByteArray(15 * 1024 * 1024 + 1)
        oversized[0] = 0x89.toByte()
        oversized[1] = 0x50
        oversized[2] = 0x4e
        oversized[3] = 0x47
        assertNull(OrionImagePayload.format(oversized))
    }
    @Test fun factToolsAreRegistered() {
        listOf("fact_add", "fact_query", "fact_forget").forEach { assertNotNull(it, ToolRegistry.find(it)) }
    }
}
