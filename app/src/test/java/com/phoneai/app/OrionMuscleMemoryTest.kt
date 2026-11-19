package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionMuscleMemoryTest {
    private val route = "send whatsapp message benedicta" to listOf("resolve_contact", "whatsapp_send")

    @Test fun nearIdenticalRequestBecomesAReflexEvenOnce() {
        val pick = OrionMuscleMemory.pick(listOf(route), "send whatsapp message benedicta", emptySet())
        assertNotNull(pick); assertEquals(listOf("resolve_contact", "whatsapp_send"), pick!!.tools)
        assertTrue(OrionMuscleMemory.directive(pick).contains("resolve_contact → whatsapp_send"))
    }

    @Test fun looseMatchNeedsRepetitionBeforeItIsAReflex() {
        val req = "send whatsapp message benedicta tonight dinner"
        assertNull(OrionMuscleMemory.pick(listOf(route), req, emptySet()))
        assertNotNull(OrionMuscleMemory.pick(listOf(route, route), req, emptySet()))
    }

    @Test fun aDegradedToolDisqualifiesTheReflex() {
        assertNull(OrionMuscleMemory.pick(listOf(route, route), "send whatsapp message benedicta", setOf("whatsapp_send")))
    }

    @Test fun unrelatedRequestsGetNothing() {
        assertNull(OrionMuscleMemory.pick(listOf(route, route), "what is the weather in lagos", emptySet()))
    }
}
