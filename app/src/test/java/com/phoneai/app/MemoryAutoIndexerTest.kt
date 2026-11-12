package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class MemoryAutoIndexerTest {
    @Test fun capturesExplicitRoleAndTradingActivityAsOwnerFacts() {
        val role = MemoryAutoIndexer.extract("I am a forex trader")
        assertNotNull(role)
        assertEquals("fact", role!!.category)
        assertTrue(role.text.contains("I am a forex trader"))
        assertTrue(role.ownerConfirmed)

        val trading = MemoryAutoIndexer.extract("I trade forex and crypto")
        assertNotNull(trading)
        assertTrue(trading!!.text.contains("I trade forex and crypto"))
        assertTrue(trading.ownerConfirmed)
    }

    @Test fun capturesPreferencesProjectsAndLearningGoals() {
        assertEquals("preference", MemoryAutoIndexer.extract("I prefer concise technical explanations")?.category)
        assertEquals("project", MemoryAutoIndexer.extract("I am building an Android assistant")?.category)
        assertEquals("project", MemoryAutoIndexer.extract("I'm learning programming")?.category)
    }

    @Test fun capturesFavouriteFactsInAFormTheGraphCanParse() {
        val favourite = MemoryAutoIndexer.extract("My favourite team is Arsenal")
        assertNotNull(favourite)
        assertEquals("fact", favourite!!.category)
        assertTrue(GraphPolicy.extract(favourite.text).contains(Triple("user", "favourite team", "arsenal")))
        assertEquals("preference", MemoryAutoIndexer.extract("I love football")?.category)
    }

    @Test fun questionsAndSensitiveContentAreNotAutomaticallyStored() {
        assertNull(MemoryAutoIndexer.extract("Do you remember that I trade forex?"))
        assertNull(MemoryAutoIndexer.extract("My password is hunter2"))
    }
}
