package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class CheckpointTransitionPolicyTest {
    @Test fun interruptedActionRequiresUserVerification() {
        assertTrue(CheckpointTransitionPolicy.needsUserConfirmation(true))
        assertFalse(CheckpointTransitionPolicy.needsUserConfirmation(false))
    }

    @Test fun unresolvedActionCannotBeOverwrittenByAnotherStep() {
        assertFalse(CheckpointTransitionPolicy.canBeginStep(true))
        assertTrue(CheckpointTransitionPolicy.canBeginStep(false))
    }

    @Test fun onlyMatchingInFlightToolCanRecordResult() {
        assertTrue(CheckpointTransitionPolicy.canRecordResult("click_text", "click_text"))
        assertFalse(CheckpointTransitionPolicy.canRecordResult("click_text", "type"))
        assertFalse(CheckpointTransitionPolicy.canRecordResult(null, "click_text"))
        assertFalse(CheckpointTransitionPolicy.canRecordResult("", "click_text"))
    }

    @Test fun uncertainActionCannotBeMarkedComplete() {
        assertFalse(CheckpointTransitionPolicy.canComplete(true))
        assertTrue(CheckpointTransitionPolicy.canComplete(false))
    }
}
