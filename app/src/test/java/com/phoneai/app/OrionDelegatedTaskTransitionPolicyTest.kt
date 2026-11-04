package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionDelegatedTaskTransitionPolicyTest {
    @Test fun onlyRunningUninterruptedTaskCanBeginAction() {
        assertTrue(OrionDelegatedTaskTransitionPolicy.canBeginAction("running", false, false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canBeginAction("paused", false, false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canBeginAction("recovery_required", false, false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canBeginAction("running", true, false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canBeginAction("running", false, true, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canBeginAction("running", false, false, true))
    }

    @Test fun lateWorkerResultCannotClearPauseOrStop() {
        assertTrue(OrionDelegatedTaskTransitionPolicy.canRecordAction("running", false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canRecordAction("paused", false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canRecordAction("running", true, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canRecordAction("running", false, true))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canRecordAction("recovery_required", false, false))
    }

    @Test fun pausedStoppedAndUncertainTasksCannotBeMarkedComplete() {
        assertTrue(OrionDelegatedTaskTransitionPolicy.canComplete("running", false, false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canComplete("paused", false, false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canComplete("recovery_required", false, false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canComplete("running", true, false, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canComplete("running", false, true, false))
        assertFalse(OrionDelegatedTaskTransitionPolicy.canComplete("running", false, false, true))
    }
}
