package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionTomPreferenceExtractorTest {
    @Test fun extractsOnlyExplicitInteractionPreferences() {
        val found = OrionTomPreferenceExtractor.extract(
            "I prefer detailed answers. Please be proactive. Always verify the result."
        )
        assertEquals(3, found.size)
        assertTrue(found.any { it.dimension == "response_detail" && it.value == "detailed" })
        assertTrue(found.any { it.dimension == "initiative" && it.value == "be proactive" })
        assertTrue(found.any { it.dimension == "verification_style" })
        assertTrue(found.all { it.confidence == 1.0 && it.evidence.isNotBlank() })
    }

    @Test fun ignoresUnstatedPersonalityAndSensitiveInferences() {
        assertTrue(OrionTomPreferenceExtractor.extract("Hey, what's up?").isEmpty())
        assertTrue(OrionTomPreferenceExtractor.extract("I am tired today").isEmpty())
    }

    @Test fun capturesTheIntentInferencePreference() {
        val found = OrionTomPreferenceExtractor.extract("Stop making me use commands; understand what I mean from context.")
        assertTrue(found.any { it.dimension == "interaction_style" })
    }
}
