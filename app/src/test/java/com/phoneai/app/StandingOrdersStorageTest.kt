package com.phoneai.app

import android.content.Context
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StandingOrdersStorageTest {
    private lateinit var context: Context
    private lateinit var file: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        file = File(context.filesDir, "orion_standing_orders.json")
        file.delete()
        StandingOrders.all(context)
        assertNull(StandingOrders.storageError())
    }

    @Test fun schedulePersistsPauseResumeAndExecutionOutcome() {
        assertTrue(StandingOrders.add(context, "brief", 60).startsWith("standing order saved"))
        assertTrue(StandingOrders.setEnabled(context, "brief", false).startsWith("paused"))
        var order = StandingOrders.all(context).single()
        assertFalse(order.enabled)
        assertEquals("paused", order.lastOutcome)
        assertTrue(StandingOrders.setEnabled(context, "brief", true).startsWith("resumed"))
        assertTrue(StandingOrders.update(context, "brief", "old-digest", "provider_timeout", success = false, now = 1_000_000L))
        order = StandingOrders.all(context).single()
        assertEquals(1, order.consecutiveFailures)
        assertEquals("provider_timeout", order.lastOutcome)
        assertEquals(1_000_000L + StandingOrderPolicy.retryDelay(1), order.nextRunAt)
        assertEquals("", order.lastDigest)
    }

    @Test fun thirdFailureStopsUntilExplicitResume() {
        assertTrue(StandingOrders.add(context, "brief", 60).startsWith("standing order saved"))
        repeat(3) { index ->
            val now = 2_000_000L + index * 100_000L
            assertTrue(StandingOrders.update(context, "brief", "digest-$index", "provider_timeout", success = false, now = now))
        }
        var order = StandingOrders.all(context).single()
        assertEquals(StandingOrderPolicy.MAX_CONSECUTIVE_FAILURES, order.consecutiveFailures)
        assertFalse(StandingOrderPolicy.due(order, Long.MAX_VALUE))
        assertTrue(StandingOrders.setEnabled(context, "brief", true).startsWith("resumed"))
        order = StandingOrders.all(context).single()
        assertEquals(0, order.consecutiveFailures)
        assertTrue(StandingOrderPolicy.due(order, Long.MAX_VALUE))
    }

    @Test fun malformedScheduleStorageFailsClosed() {
        file.writeText("[{not-json")
        assertTrue(StandingOrders.all(context).isEmpty())
        assertNotNull(StandingOrders.storageError())
        assertTrue(StandingOrders.add(context, "brief", 60).startsWith("error:"))
        assertTrue(file.readText().contains("not-json"))
    }
}
