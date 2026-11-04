package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ActionVerificationPolicyTest {
    @Test fun aVisibleChangePasses() {
        assertEquals(ActionVerificationPolicy.Outcome.PASS, ActionVerificationPolicy.outcome(true, false))
    }

    @Test fun aPackageChangePassesEvenWithoutATextDiff() {
        assertEquals(ActionVerificationPolicy.Outcome.PASS, ActionVerificationPolicy.outcome(false, true))
    }

    @Test fun noVisibleChangeIsUnverifiedNotFailure() {
        assertEquals(ActionVerificationPolicy.Outcome.UNVERIFIED, ActionVerificationPolicy.outcome(false, false))
    }

    @Test fun unverifiedNoteNamesTheToolAndDoesNotReadAsAnError() {
        val note = ActionVerificationPolicy.unverifiedNote("tap")
        assertTrue(note.contains("tap"))
        assertTrue(note.startsWith("UNVERIFIED"))
        assertFalse(note.contains("error:"))
    }
}
