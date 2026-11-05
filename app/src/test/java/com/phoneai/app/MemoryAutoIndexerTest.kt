package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class MemoryAutoIndexerTest {
    @Test fun extractsIdentityPreferencesAndRoutines() {
        assertEquals("fact", MemoryAutoIndexer.extract("my name is Kingi")?.category)
        assertEquals("preference", MemoryAutoIndexer.extract("I prefer dark mode")?.category)
        assertEquals("routine", MemoryAutoIndexer.extract("I usually work out at 6am")?.category)
    }

    @Test fun extractsRelationshipsAndBirthdays() {
        val wife = MemoryAutoIndexer.extract("my wife is Ada")
        assertNotNull(wife)
        assertEquals("people", wife!!.category)
        assertTrue(wife.text.contains("Ada"))

        val birthday = MemoryAutoIndexer.extract("my birthday is 4 May")
        assertNotNull(birthday)
        assertTrue(birthday!!.text.contains("birthday"))
    }

    @Test fun ordinaryChatAndQuestionsAreNotIndexed() {
        assertNull(MemoryAutoIndexer.extract("what was real madrid last match score?"))
        assertNull(MemoryAutoIndexer.extract("tell me a joke"))
    }
}
