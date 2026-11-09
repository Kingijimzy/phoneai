package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionFactExtractorTest {
    @Test fun favouriteBecomesTriplet() {
        val f = OrionFactExtractor.extract("my favourite sport is football", "Kingi")
        assertTrue(f.contains(Triple("Kingi", "favourite_sport", "football")))
    }

    @Test fun questionsAreIgnored() = assertTrue(OrionFactExtractor.extract("what is my favourite sport?", "Kingi").isEmpty())

    @Test fun livesIn() {
        assertTrue(OrionFactExtractor.extract("I live in Lagos", "Kingi").contains(Triple("Kingi", "lives_in", "Lagos")))
    }
}
