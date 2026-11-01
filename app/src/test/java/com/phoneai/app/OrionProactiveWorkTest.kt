package com.phoneai.app

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionProactiveWorkTest {
    @Test fun scheduleRegistrationIsDurableAndIdempotent() {
        val context = RuntimeEnvironment.getApplication() as Context
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
        )

        OrionProactiveWork.ensureScheduled(context)
        OrionProactiveWork.ensureScheduled(context)

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkSync("orion_stage6_proactive_heartbeat")
        assertEquals("Repeated service/boot registration must keep one heartbeat", 1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
        assertTrue(work.single().tags.contains("orion_stage6_proactive_heartbeat"))
    }
}
