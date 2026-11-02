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

        AgentPolicy.setEnabled(context, "orion_core", true)
        AgentPolicy.setEnabled(context, "proactive", true)
        OrionProactiveWork.ensureScheduled(context)
        OrionProactiveWork.ensureScheduled(context)

        // Use the ListenableFuture API supported by the project's WorkManager version.
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("orion_stage6_proactive_heartbeat").get()
        assertEquals("Repeated service/boot registration must keep one heartbeat", 1, work.size)
        assertTrue("Registered heartbeat must not be cancelled", work.single().state != WorkInfo.State.CANCELLED)
        assertTrue(work.single().tags.contains("orion_stage6_proactive_heartbeat"))
    }
    @Test fun disablingEitherOwnerPackCancelsTheHeartbeat() {
        val context = RuntimeEnvironment.getApplication() as Context
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build()
        )
        AgentPolicy.setEnabled(context, "orion_core", true)
        AgentPolicy.setEnabled(context, "proactive", true)
        OrionProactiveWork.ensureScheduled(context)
        val manager = WorkManager.getInstance(context)
        assertEquals(1, manager.getWorkInfosForUniqueWork("orion_stage6_proactive_heartbeat").get().size)

        AgentPolicy.setEnabled(context, "proactive", false)
        val work = manager.getWorkInfosForUniqueWork("orion_stage6_proactive_heartbeat").get()
        assertTrue("Disabling Proactive must cancel its periodic heartbeat", work.all { it.state == WorkInfo.State.CANCELLED })
    }
}
