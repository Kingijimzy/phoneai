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

    @Test fun exportRoundTripsAndAlwaysImportsDisabled() {
        val (parsed, message) = SkillPackageCodec.parse(SkillPackageCodec.export(valid))
        assertNotNull(message, parsed)
        assertEquals(valid.id, parsed!!.id)
        assertEquals(valid.allowedTools, parsed.allowedTools)
        assertFalse(parsed.enabled)
        assertTrue(parsed.requiresApproval)
    }

    @Test fun rejectsUnknownFormatAndExecutableOrUnknownTools() {
        assertNull(SkillPackageCodec.parse("""{"format":"arbitrary-code","formatVersion":1,"skill":{}}""").first)
        val unknown = SkillPackageCodec.export(valid).replace("fetch_url", "run_shell_unrestricted")
        assertNull(SkillPackageCodec.parse(unknown).first)
    }

    @Test fun rejectsInvalidBudgetsAndOversizedInput() {
        val bad = SkillPackageCodec.export(valid).replace("\"maxCalls\": 5", "\"maxCalls\": 500")
        assertNull(SkillPackageCodec.parse(bad).first)
        assertNull(SkillPackageCodec.parse("x".repeat(100_001)).first)
    }
}
