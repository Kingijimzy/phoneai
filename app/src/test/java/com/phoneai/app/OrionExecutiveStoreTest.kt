package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionExecutiveStoreTest {
    @Test fun executivePhasesRequireVerificationBeforeLearningOrCompletion() {
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.GROUND, OrionExecutiveStore.Phase.RETRIEVE))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.PERFORM, OrionExecutiveStore.Phase.VERIFY))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.VERIFY, OrionExecutiveStore.Phase.LEARN))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.LEARN, OrionExecutiveStore.Phase.COMPLETED))
        assertFalse(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.PERFORM, OrionExecutiveStore.Phase.COMPLETED))
        assertFalse(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.GROUND, OrionExecutiveStore.Phase.COMPLETED))
    }

    @Test fun blockedAndOwnerWaitingObjectivesHaveExplicitRecoveryPaths() {
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.ASSESS, OrionExecutiveStore.Phase.WAITING_FOR_OWNER))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.WAITING_FOR_OWNER, OrionExecutiveStore.Phase.GROUND))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.PERFORM, OrionExecutiveStore.Phase.BLOCKED))
        assertTrue(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.BLOCKED, OrionExecutiveStore.Phase.GROUND))
        assertFalse(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.COMPLETED, OrionExecutiveStore.Phase.GROUND))
        assertFalse(OrionExecutiveStore.canTransition(OrionExecutiveStore.Phase.FAILED, OrionExecutiveStore.Phase.PERFORM))
    }
}
