package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionMemoryConsolidationTest {
    private fun mem(id: Int, text: String, status: String = "approved", pinned: Boolean = false) =
        Memory(id, "fact", text, 1L, id.toLong(), pinned, "owner", 1.0, "general", status, "")

    @Test fun readsTheSubjectSlotsThatMatter() {
        assertEquals(OrionMemoryConsolidation.Slot("city", "lagos"), OrionMemoryConsolidation.slotOf("I live in Lagos."))
        assertEquals(OrionMemoryConsolidation.Slot("city", "abuja"), OrionMemoryConsolidation.slotOf("I moved to Abuja last month"))
        assertEquals(OrionMemoryConsolidation.Slot("job", "mechanic"), OrionMemoryConsolidation.slotOf("I work as a mechanic and love it"))
        assertEquals(OrionMemoryConsolidation.Slot("employer", "acme ltd"), OrionMemoryConsolidation.slotOf("I work at Acme Ltd"))
        assertEquals(OrionMemoryConsolidation.Slot("relative:sister", "amaka"), OrionMemoryConsolidation.slotOf("My sister is called Amaka"))
        assertNull(OrionMemoryConsolidation.slotOf("I like jollof rice"))
    }

    @Test fun aNewCityReplacesTheOldOneButTheSameCityDoesNot() {
        val old = listOf(mem(1, "I live in Lagos"), mem(2, "I like jollof rice"))
        assertEquals(1, OrionMemoryConsolidation.conflicting(old, "I live in Abuja", "approved")!!.id)
        assertNull(OrionMemoryConsolidation.conflicting(old, "I live in Lagos", "approved"))
        assertNull(OrionMemoryConsolidation.conflicting(old, "I like suya", "approved"))
    }

    @Test fun relativeNamesAreKeptPerRelative() {
        val old = listOf(mem(1, "My sister is called Amaka"), mem(2, "My brother is called Chidi"))
        assertEquals(1, OrionMemoryConsolidation.conflicting(old, "My sister is named Ngozi", "approved")!!.id)
    }

    @Test fun unreviewedTextNeverOverwritesApprovedMemoryAndPinnedIsUntouched() {
        assertNull(OrionMemoryConsolidation.conflicting(listOf(mem(1, "I live in Lagos")), "I live in Abuja", "pending"))
        assertNotNull(OrionMemoryConsolidation.conflicting(listOf(mem(1, "I live in Lagos", "pending")), "I live in Abuja", "pending"))
        assertNull(OrionMemoryConsolidation.conflicting(listOf(mem(1, "I live in Lagos", pinned = true)), "I live in Abuja", "approved"))
    }

    @Test fun historyIsOneShortLineAndDoesNotChain() {
        val first = OrionMemoryConsolidation.withHistory("I live in Lagos", "I live in Abuja", 0L)
        assertEquals("I live in Abuja (was: lagos, until 1970-01-01)", first)
        val second = OrionMemoryConsolidation.withHistory(first, "I live in Kano", 86_400_000L)
        assertEquals("I live in Kano (was: abuja, until 1970-01-02)", second)
        assertTrue(second.length < 100)
    }
}
