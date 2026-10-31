package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAutoIndexerTest {
    @Test fun extractsOnlyExplicitPreferencesRoutinesProjectsAndGoals() {
        assertEquals("preference", MemoryAutoIndexer.extract("I prefer concise answers with clear examples")?.category)
        assertEquals("routine", MemoryAutoIndexer.extract("I usually review forex charts before the London session")?.category)
        assertEquals("project", MemoryAutoIndexer.extract("I'm building Orion as a personal Android companion")?.category)
        assertEquals("task", MemoryAutoIndexer.extract("My goal is to become a consistent full-time trader")?.category)
        assertEquals("fact", MemoryAutoIndexer.extract("Remember that my preferred coding language is Kotlin")?.category)
    }

    @Test fun doesNotIndexCasualQuestionsOrUnqualifiedConversation() {
        assertEquals(null, MemoryAutoIndexer.extract("What do you think about forex?"))
        assertEquals(null, MemoryAutoIndexer.extract("Hey Orion, how are you today?"))
        assertEquals(null, MemoryAutoIndexer.extract("I like this"))
    }

    @Test fun sensitiveContentIsNeverAutomaticallyProposed() {
        assertEquals(null, MemoryAutoIndexer.extract("Remember that my API key is abc123456789"))
        assertEquals(null, MemoryAutoIndexer.extract("I prefer to store my password in this note"))
    }

    @Test fun candidateIsBoundedAndOwnerReviewable() {
        val candidate = MemoryAutoIndexer.extract("I prefer Orion to explain code step by step.")
        assertTrue(candidate != null)
        assertTrue(candidate!!.text.length <= 300)
        assertEquals(0.82, candidate.confidence, 0.0)
        assertTrue(candidate.reason.contains("preference"))
    }
}

class ContextWindowBudgetTest {
    @Test fun prioritizesMemoryAndObjectiveEvidenceWithinTokenBudget() {
        val sections = listOf(
            "LOW PRIORITY app map " + "x".repeat(8000),
            "RELEVANT PERSISTENT MEMORY user prefers concise explanations",
            "TASK RESUME INSTRUCTIONS continue exact active objective"
        )
        val packed = ContextWindowBudget.pack(sections, 700)
        assertTrue(packed.estimatedTokens <= 700)
        assertTrue(packed.text.contains("TASK RESUME INSTRUCTIONS"))
        assertTrue(packed.text.contains("RELEVANT PERSISTENT MEMORY"))
        assertTrue(packed.includedSections >= 2)
    }

    @Test fun preservesOriginalOrderAfterPrioritySelection() {
        val packed = ContextWindowBudget.pack(
            listOf("first section", "RELEVANT PERSISTENT MEMORY", "last section"),
            500
        )
        assertTrue(packed.text.indexOf("first section") < packed.text.indexOf("RELEVANT PERSISTENT MEMORY"))
        assertTrue(packed.text.indexOf("RELEVANT PERSISTENT MEMORY") < packed.text.indexOf("last section"))
    }

    @Test fun clipsOversizedSectionsAndReportsOmissions() {
        val packed = ContextWindowBudget.pack(
            listOf("ORION CENTRAL BRAIN " + "a".repeat(9000), "unimportant " + "b".repeat(9000)),
            500
        )
        assertTrue(packed.estimatedTokens <= 500)
        assertTrue(packed.includedSections >= 1)
        assertTrue(packed.omittedSections >= 0)
        assertFalse(packed.text.isBlank())
    }

    @Test fun emptyAndInvalidBudgetsAreSafe() {
        assertEquals("", ContextWindowBudget.pack(emptyList(), 100).text)
        assertTrue(ContextWindowBudget.estimateTokens("12345") == 2)
        assertTrue(ContextWindowBudget.pack(listOf("small"), 0).estimatedTokens <= 1)
    }
}
