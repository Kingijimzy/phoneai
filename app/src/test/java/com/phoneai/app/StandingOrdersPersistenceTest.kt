package com.phoneai.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StandingOrdersPersistenceTest {
    private lateinit var context: Context
    private lateinit var storage: File

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storage = File(context.filesDir, "orion_standing_orders.json")
        storage.delete()
        StandingOrders.all(context)
    }

    @Test fun malformedStorageIsReportedAndMutationsFailClosed() {
        storage.writeText("{ definitely not json")
        assertTrue(StandingOrders.all(context).isEmpty())
        assertTrue(StandingOrders.storageError().orEmpty().contains("could not be read safely"))
        assertTrue(StandingOrders.add(context, "brief", 60).startsWith("error:"))
        assertTrue(StandingOrders.remove(context, "brief").startsWith("error:"))
        assertTrue(storage.readText().contains("definitely not json"))
    }

    @Test fun validEmptyStorageIsNotReportedAsAnError() {
        storage.writeText("[]")
        assertTrue(StandingOrders.all(context).isEmpty())
        assertEquals(null, StandingOrders.storageError())
        assertTrue(StandingOrders.add(context, "brief", 60).startsWith("standing order saved"))
    }
}
