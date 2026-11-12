package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskReminderAlarmPolicyTest {
    @Test fun preAndroid12CanUseExactAlarmsWithoutSpecialAccess() {
        assertTrue(TaskReminderAlarmPolicy.useExactAlarm(30, exactAccessGranted = false))
    }

    @Test fun android12AndNewerRequireExactAlarmSpecialAccess() {
        assertFalse(TaskReminderAlarmPolicy.useExactAlarm(31, exactAccessGranted = false))
        assertFalse(TaskReminderAlarmPolicy.useExactAlarm(34, exactAccessGranted = false))
        assertTrue(TaskReminderAlarmPolicy.useExactAlarm(31, exactAccessGranted = true))
        assertTrue(TaskReminderAlarmPolicy.useExactAlarm(34, exactAccessGranted = true))
    }
}
