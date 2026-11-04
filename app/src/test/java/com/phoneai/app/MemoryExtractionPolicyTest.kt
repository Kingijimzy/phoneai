package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class MemoryExtractionPolicyTest {
    @Test fun extractsIdentityAndPreferences() {
        val byKey = MemoryExtractionPolicy.extract("My name is Kingi. I live in Lagos and I love jollof rice.")
            .associateBy { it.key }
        assertEquals("Kingi", byKey["name"]?.value)
        assertEquals("Lagos", byKey["city"]?.value)
        assertTrue(byKey["likes"]?.value?.contains("jollof rice") == true)
    }

    @Test fun extractsPeopleAndWork() {
        val byKey = MemoryExtractionPolicy.extract("I work as a backend engineer. My wife is Ada.")
            .associateBy { it.key }
        assertEquals("backend engineer", byKey["role"]?.value)
        assertEquals("Ada", byKey["person:wife"]?.value)
    }

    @Test fun extractsDislikesAndRoutines() {
        val byKey = MemoryExtractionPolicy.extract("I don't like loud music. I usually go to the gym at 6am.")
            .associateBy { it.key }
        assertEquals("loud music", byKey["dislikes"]?.value)
        assertTrue(byKey["routine"]?.value?.contains("gym") == true)
    }

    @Test fun assistantStyleTextIsNotExtracted() {
        assertTrue(MemoryExtractionPolicy.extract("You like jollof rice and you live in Lagos.").isEmpty())
    }

    @Test fun secretsAndContactDetailsAreNotExtracted() {
        assertTrue(MemoryExtractionPolicy.extract("My name is 12345678").isEmpty())
        assertTrue(MemoryExtractionPolicy.extract("I like email: a@b.com").none { it.value.contains("@") })
    }

    @Test fun duplicateKeysKeepTheLatestValue() {
        val facts = MemoryExtractionPolicy.extract("My name is Kingi. Actually my name is James.")
        assertEquals(1, facts.count { it.key == "name" })
        assertEquals("James", facts.first { it.key == "name" }.value)
    }
}
