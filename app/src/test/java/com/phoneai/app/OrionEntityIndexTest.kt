package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionEntityIndexTest {
    private fun mem(id: Int, text: String) = Memory(id, "fact", text, 1L, 1L, false, "owner", 1.0, "general", "approved", "")

    @Test fun extractsPeoplePlacesProjectsAndDates() {
        val e = OrionEntityIndex.extract("My sister Amaka lives in Lagos. Her birthday is 3 January. The Lagos trip is on 2026-12-12 for project Atlas.").map { it.key }
        assertTrue(e.toString(), "PERSON:amaka" in e)
        assertTrue(e.toString(), "PLACE:lagos" in e)
        assertTrue(e.toString(), "PROJECT:lagos trip" in e)
        assertTrue(e.toString(), "PROJECT:atlas" in e)
        assertTrue(e.toString(), "DATE:3 january" in e)
        assertTrue(e.toString(), "DATE:2026-12-12" in e)
    }

    @Test fun doesNotTreatSentenceStartersOrMonthsAsPlaces() {
        val e = OrionEntityIndex.extract("In March I start. At The office I work.").map { it.key }
        assertFalse(e.toString(), e.any { it.startsWith("PLACE:") })
    }

    @Test fun followsLinksFromTheLagosTripToRelatedMemories() {
        val memories = listOf(
            mem(1, "Planning a trip to Lagos in December"),
            mem(2, "Booked the flight to Lagos for 12 December"),
            mem(3, "My sister Amaka lives in Lagos"),
            mem(4, "Amaka's birthday is 3 January"),
            mem(5, "I like jollof rice")
        )
        val linked = OrionEntityIndex.linked(memories, "what's the status of the Lagos trip")
        assertEquals(1.0, linked[1]!!, 0.0)
        assertEquals(1.0, linked[2]!!, 0.0)
        assertEquals(1.0, linked[3]!!, 0.0)
        assertEquals(0.5, linked[4]!!, 0.0)
        assertFalse(5 in linked)
    }

    @Test fun noNamedEntityMeansNoLinks() {
        assertTrue(OrionEntityIndex.linked(listOf(mem(1, "I live in Lagos")), "what should I eat").isEmpty())
    }
}
