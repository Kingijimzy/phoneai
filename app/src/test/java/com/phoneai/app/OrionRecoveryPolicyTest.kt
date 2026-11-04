package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionRecoveryPolicyTest {
    @Test fun emptyQueueNeverProducesRecoveryNotification() {
        assertFalse(OrionRecoveryPolicy.shouldNotifyUser(false, false))
    }

    @Test fun connectedServiceOwnsTheDrainWithoutWarningTheUser() {
        assertFalse(OrionRecoveryPolicy.shouldNotifyUser(true, true))
    }

    @Test fun pendingWorkNotAcceptedByServicePromptsUserToResume() {
        assertTrue(OrionRecoveryPolicy.shouldNotifyUser(true, false))
    }
}
