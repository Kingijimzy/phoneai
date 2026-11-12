package com.phoneai.app

import android.os.Build

/** Pure policy for choosing exact vs. Android-throttled reminder delivery. */
internal object TaskReminderAlarmPolicy {
    fun useExactAlarm(sdkInt: Int, exactAccessGranted: Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.S || exactAccessGranted
}
