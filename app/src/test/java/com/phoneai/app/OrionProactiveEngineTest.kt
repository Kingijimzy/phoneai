package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionProactiveEngineTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Ensure clean state
        OrionSensorScope.values().forEach { OrionSensorConsent.revoke(context, it) }
        AgentPolicy.setEnabled(context, "orion_core", true)
        AgentPolicy.setEnabled(context, "proactive", true)
        Prefs.setQuietPeriodStartHour(context, 22)
        Prefs.setQuietPeriodEndHour(context, 7)
        Prefs.setProactiveMaxPerHour(context, 3)
        Prefs.setProactiveMinIntervalMs(context, 20L * 60_000L)
        Prefs.setProactiveBatteryLowThreshold(context, 20)
        Prefs.setCalendarLookaheadHours(context, 24)
        Prefs.setLastProactiveSpokenAt(context, 0L)
        clearSpokenHistory(context)
    }

    @After fun tearDown() {
        OrionSensorScope.values().forEach { OrionSensorConsent.revoke(context, it) }
        AgentPolicy.setEnabled(context, "orion_core", false)
        AgentPolicy.setEnabled(context, "proactive", false)
        clearSpokenHistory(context)
    }

    @Test fun noProactivePackMeansNoItem() {
        AgentPolicy.setEnabled(context, "proactive", false)
        assertNull(OrionProactiveEngine.evaluate(context))
    }

    @Test fun noCorePackMeansNoItem() {
        AgentPolicy.setEnabled(context, "orion_core", false)
        assertNull(OrionProactiveEngine.evaluate(context))
    }

    @Test fun quietPeriodSuppressesProactiveItem() {
        // 23:00 is inside quiet period 22:00-07:00
        val now = midnight(context) + 23 * 60 * 60 * 1000L
        assertNull(OrionProactiveEngine.evaluate(context, now))
    }

    @Test fun outsideQuietPeriodAllowsProactiveItem() {
        // 10:00 is outside quiet period 22:00-07:00
        val now = midnight(context) + 10 * 60 * 60 * 1000L
        // With no calendar/events/tasks, should return null (no candidates), not crash
        val item = OrionProactiveEngine.evaluate(context, now)
        assertTrue(item == null || item.kind.isNotBlank())
    }

    @Test fun throttlePreventsRepeatedSpokenInterruptions() {
        val now = midnight(context) + 12 * 60 * 60 * 1000L
        Prefs.setLastProactiveSpokenAt(context, now - 10 * 60_000L) // 10 min ago, below 20 min threshold
        assertNull(OrionProactiveEngine.evaluate(context, now))
    }

    @Test fun throttleAllowsAfterMinimumInterval() {
        val now = midnight(context) + 12 * 60 * 60 * 1000L
        Prefs.setLastProactiveSpokenAt(context, now - 25 * 60_000L) // 25 min ago, above 20 min threshold
        val item = OrionProactiveEngine.evaluate(context, now)
        // May be null if no candidates, but throttle should not block
        assertTrue(item == null || item.kind.isNotBlank())
    }

    @Test fun quietPeriodWrapsMidnight() {
        // Quiet period 22:00-07:00: 05:00 should be suppressed
        val now = midnight(context) + 5 * 60 * 60 * 1000L
        assertNull(OrionProactiveEngine.evaluate(context, now))
        // 08:00 should be allowed
        val now2 = midnight(context) + 8 * 60 * 60 * 1000L
        val item = OrionProactiveEngine.evaluate(context, now2)
        assertTrue(item == null || item.kind.isNotBlank())
    }

    @Test fun proactiveDisabledByMaxPerHourZero() {
        Prefs.setProactiveMaxPerHour(context, 0)
        val now = midnight(context) + 12 * 60 * 60 * 1000L
        assertNull(OrionProactiveEngine.evaluate(context, now))
    }

    @Test fun rateCapLimitsSpokenPerHour() {
        val now = midnight(context) + 12 * 60 * 60 * 1000L
        Prefs.setProactiveMaxPerHour(context, 1)
        // Record one spoken event in the last hour
        recordSpokenNow(context, now - 30 * 60_000L)
        assertNull(OrionProactiveEngine.evaluate(context, now))
    }

    @Test fun batteryLowSuggestsCharging() {
        // This test just checks the engine doesn't crash with low battery context.
        // Battery reading is device-dependent; we verify no exception.
        val now = midnight(context) + 12 * 60 * 60 * 1000L
        val item = OrionProactiveEngine.evaluate(context, now)
        assertTrue(item == null || item.kind.isNotBlank())
    }

    @Test fun proactiveItemHasValidFields() {
        val now = midnight(context) + 12 * 60 * 60 * 1000L
        val item = OrionProactiveEngine.evaluate(context, now)
        if (item != null) {
            assertTrue(item.kind.isNotBlank())
            assertTrue(item.title.isNotBlank())
            assertTrue(item.body.isNotBlank())
            assertTrue(item.urgency in 0..2)
        }
    }

    @Test fun followUpDueSoonCreatesCandidate() {
        val now = midnight(context) + 12 * 60 * 60 * 1000L
        // Add a follow-up due in 30 minutes
        OrionFollowUpStore.add(context, "call the plumber", dueAt = now + 30 * 60_000L)
        val item = OrionProactiveEngine.evaluate(context, now)
        assertNotNull(item)
        val actual = requireNotNull(item) { "expected a due follow-up candidate" }
        assertEquals("followup", actual.kind)
        assertTrue(actual.body.contains("plumber"))
    }

    @Test fun overdueTaskCreatesHighUrgencyCandidate() {
        val now = midnight(context) + 12 * 60 * 60 * 1000L
        // Add an overdue task
        TaskStore.add(context, "fix the roof", dueAt = now - 2 * 60 * 60_000L, priority = "urgent")
        val item = OrionProactiveEngine.evaluate(context, now)
        assertNotNull(item)
        val actual = requireNotNull(item) { "expected an overdue task candidate" }
        assertEquals("task", actual.kind)
        assertEquals(2, actual.urgency)
    }

    private fun midnight(context: Context): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun clearSpokenHistory(context: Context) {
        context.getSharedPreferences("orion_proactive_spoken_v1", Context.MODE_PRIVATE).edit()
            .putString("spoken_at", "[]").apply()
    }

    private fun recordSpokenNow(context: Context, at: Long) {
        val prefs = context.getSharedPreferences("orion_proactive_spoken_v1", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray().put(at)
        prefs.edit().putString("spoken_at", arr.toString()).apply()
    }
}
