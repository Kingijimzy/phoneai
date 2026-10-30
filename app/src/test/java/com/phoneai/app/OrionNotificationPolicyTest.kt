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
}
