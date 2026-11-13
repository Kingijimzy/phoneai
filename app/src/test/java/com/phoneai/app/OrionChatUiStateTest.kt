package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionChatUiStateTest {
    @Test fun workPhasesUseClearHumanProgressLanguage() {
        assertEquals("Thinking through the best approach", orionWorkingPhrase("Planning objective"))
        assertEquals("Fixing the issue", orionWorkingPhrase("Repairing implementation"))
        assertEquals("Verifying that the result works", orionWorkingPhrase("Running verification"))
        assertEquals("Finding the information needed", orionWorkingPhrase("Researching documentation"))
        assertEquals("Recovering and choosing another route", orionWorkingPhrase("Retrying after timeout"))
        assertEquals("Waiting for your approval", orionWorkingPhrase("Approval pending"))
    }

    @Test fun statusMapsToDistinctOrbStatesWithoutOverridingLiveRuntimeState() {
        assertEquals(OrbView.Mode.WAITING_APPROVAL, orionModeForStatus("Approval pending", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.ERROR, orionModeForStatus("Execution failed", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.ACTING, orionModeForStatus("Executing command", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.LISTENING, orionModeForStatus("Listening", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.SPEAKING, orionModeForStatus("Speaking", OrbView.Mode.IDLE))
        assertEquals(OrbView.Mode.SPEAKING, orionModeForStatus("Approval pending", OrbView.Mode.SPEAKING))
    }
}
