package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionCoordinatorTest {
    @Test fun typedRequestsKeepTheirActualSurfaceWithoutSplittingTheAgentLoop() {
        assertEquals(OrionCoordinator.Surface.CHAT, OrionCoordinator.surfaceForTypedRequest(conversationOnly = true))
        assertEquals(OrionCoordinator.Surface.COMMAND, OrionCoordinator.surfaceForTypedRequest(conversationOnly = false))
    }

    @Test fun unknownMasterControlStateFailsClosed() {
        assertTrue(OrionCoordinator.shouldAdmit(true))
        assertFalse(OrionCoordinator.shouldAdmit(false))
        assertFalse(OrionCoordinator.shouldAdmit(null))
    }

    @Test fun ownerSurfacesAreMarkedAndSelfStartedOnesAreNot() {
        assertTrue(OrionCoordinator.Surface.CHAT.ownerInitiated)
        assertTrue(OrionCoordinator.Surface.VOICE.ownerInitiated)
        assertTrue(OrionCoordinator.Surface.TRADING.ownerInitiated)
        assertFalse(OrionCoordinator.Surface.INITIATIVE.ownerInitiated)
        assertFalse(OrionCoordinator.Surface.AUTONOMY.ownerInitiated)
    }
    @Test fun ringKeepsOnlyRecentEntriesAndCountsBySurface() {
        repeat(60) { OrionCoordinator.record(OrionCoordinator.Surface.CHAT, "msg $it", true, it.toLong()) }
        OrionCoordinator.record(OrionCoordinator.Surface.VOICE, "hey", true, 100L)
        val r = OrionCoordinator.recent(100)
        assertTrue(r.size <= 40)
        val c = OrionCoordinator.surfaceCounts(r)
        assertEquals(1, c[OrionCoordinator.Surface.VOICE])
        assertTrue((c[OrionCoordinator.Surface.CHAT] ?: 0) >= 30)
    }
    @Test fun summaryNamesSurfaces() {
        OrionCoordinator.record(OrionCoordinator.Surface.INITIATIVE, "self", true)
        assertTrue(OrionCoordinator.summary().contains("initiative"))
    }
}
