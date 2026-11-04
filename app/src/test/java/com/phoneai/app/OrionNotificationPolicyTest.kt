package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionNotificationPolicyTest {
    @Test fun preAndroid13DoesNotRequireRuntimeNotificationPermission() {
        assertTrue(OrionNotificationPolicy.canPost(32, false))
    }

    @Test fun android13AndLaterRequireGrantedNotificationPermission() {
        assertFalse(OrionNotificationPolicy.canPost(33, false))
        assertTrue(OrionNotificationPolicy.canPost(33, true))
        assertFalse(OrionNotificationPolicy.canPost(36, false))
        assertTrue(OrionNotificationPolicy.canPost(36, true))
    }

    @Test fun approvalAlertsRespectPreferenceForegroundAndOsPermission() {
        assertFalse(OrionNotificationPolicy.shouldNotifyApproval(false, false, 36, true))
        assertFalse(OrionNotificationPolicy.shouldNotifyApproval(true, true, 36, true))
        assertFalse(OrionNotificationPolicy.shouldNotifyApproval(true, false, 36, false))
        assertTrue(OrionNotificationPolicy.shouldNotifyApproval(true, false, 36, true))
    }

    @Test fun taskOutcomeAlertsRespectPreferenceForegroundAndOsPermission() {
        assertFalse(OrionNotificationPolicy.shouldNotifyTaskOutcome(false, false, 36, true))
        assertFalse(OrionNotificationPolicy.shouldNotifyTaskOutcome(true, true, 36, true))
        assertFalse(OrionNotificationPolicy.shouldNotifyTaskOutcome(true, false, 36, false))
        assertTrue(OrionNotificationPolicy.shouldNotifyTaskOutcome(true, false, 36, true))
    }
}
