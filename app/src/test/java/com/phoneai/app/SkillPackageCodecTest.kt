package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class SkillPackageCodecTest {
    private val valid = PhoneAiSkill(
        id = "public_research",
        version = "1.2.0",
        description = "Research public information and return source links.",
        allowedTools = setOf("web_search", "fetch_url"),
        maxCalls = 5,
        maxDurationMs = 20_000,
        requiresApproval = true,
        enabled = true
    )

    @Test fun exportRoundTripsUsingTheSharedToolInventoryWithoutApprovalFlags() {
        val exported = SkillPackageCodec.export(valid)
        val (parsed, message) = SkillPackageCodec.parse(exported)
        assertNotNull(message, parsed)
        assertEquals(valid.id, parsed!!.id)
        assertEquals(ToolRegistry.names(), parsed.allowedTools)
        assertFalse(parsed.enabled)
        assertFalse(parsed.requiresApproval)
        assertFalse(exported.contains("\"requiresApproval\": true"))
    }

    @Test fun legacyPackageToolListsAreIgnoredRatherThanBecomingScopes() {
        val legacy = SkillPackageCodec.export(valid).replace("\"allowedTools\": [", "\"allowedTools\": [\"run_shell_unrestricted\", ")
        val parsed = SkillPackageCodec.parse(legacy).first
        assertNotNull(parsed)
        assertEquals(ToolRegistry.names(), parsed!!.allowedTools)
    }

    @Test fun rejectsUnknownFormatAndInvalidBudgets() {
        assertNull(SkillPackageCodec.parse("""{"format":"arbitrary-code","formatVersion":1,"skill":{}}""").first)
        val bad = SkillPackageCodec.export(valid).replace("\"maxCalls\": 5", "\"maxCalls\": 500")
        assertNull(SkillPackageCodec.parse(bad).first)
        assertNull(SkillPackageCodec.parse("x".repeat(100_001)).first)
    }
}
