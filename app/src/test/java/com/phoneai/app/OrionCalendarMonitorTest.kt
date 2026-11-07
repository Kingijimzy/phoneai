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
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowContentResolver
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract

/** Robolectric ships no calendar provider; this in-memory one honours the columns and bounds the monitor reads. */
class FakeCalendarProvider : ContentProvider() {
    companion object { val rows = mutableListOf<ContentValues>() }
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        rows += ContentValues(values ?: return null)
        return Uri.withAppendedPath(uri, rows.size.toString())
    }
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val cols = projection ?: arrayOf("_id", "title", "dtstart", "eventLocation")
        val cursor = MatrixCursor(cols)
        val from = args?.getOrNull(0)?.toLongOrNull() ?: Long.MIN_VALUE
        val to = args?.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
        rows.withIndex().filter { (_, v) -> (v.getAsLong("dtstart") ?: 0L).let { it >= from && it < to } && v.getAsString("eventTimezone") != null }
            .sortedBy { it.value.getAsLong("dtstart") }.forEach { (i, v) ->
                cursor.addRow(cols.map { c -> if (c == "_id") i + 1 else v.get(c) }.toTypedArray())
            }
        return cursor
    }
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun getType(uri: Uri): String? = null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionCalendarMonitorTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FakeCalendarProvider.rows.clear()
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        OrionSensorScope.values().forEach { OrionSensorConsent.revoke(context, it) }
    }

    @After fun tearDown() {
        OrionSensorScope.values().forEach { OrionSensorConsent.revoke(context, it) }
    }

    @Test fun notEnabledWithoutConsent() {
        assertFalse(OrionCalendarMonitor.isEnabled(context))
        assertTrue(OrionCalendarMonitor.upcoming(context, 24).isEmpty())
        assertTrue(OrionCalendarMonitor.soon(context, 24).isBlank())
    }

    @Test fun enabledWithConsent() {
        OrionSensorConsent.grant(context, OrionSensorScope.CALENDAR_EVENTS, 4 * 60 * 60 * 1000L)
        assertTrue(OrionCalendarMonitor.isEnabled(context))
        // No events inserted, so empty
        assertTrue(OrionCalendarMonitor.upcoming(context, 24).isEmpty())
    }

    @Test fun upcomingEventIsReturned() {
        OrionSensorConsent.grant(context, OrionSensorScope.CALENDAR_EVENTS, 4 * 60 * 60 * 1000L)
        val now = System.currentTimeMillis()
        val future = now + 90 * 60_000L // 90 min from now

        val resolver = context.contentResolver
        val values = android.content.ContentValues()
        values.put(CalendarContract.Events.DTSTART, future)
        values.put(CalendarContract.Events.TITLE, "Team standup")
        values.put(CalendarContract.Events.EVENT_LOCATION, "Conference room B")
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        resolver.insert(CalendarContract.Events.CONTENT_URI, values)

        val events = OrionCalendarMonitor.upcoming(context, 24, now)
        assertFalse(events.isEmpty())
        assertEquals("Team standup", events.first().title)
        assertTrue(events.first().minutesFromNow > 0)
        assertTrue(events.first().minutesFromNow <= 120)
    }

    @Test fun eventOutsideLookaheadIsNotReturned() {
        OrionSensorConsent.grant(context, OrionSensorScope.CALENDAR_EVENTS, 4 * 60 * 60 * 1000L)
        val now = System.currentTimeMillis()
        val farFuture = now + 48 * 60 * 60_000L // 48 hours, beyond 24h lookahead

        val resolver = context.contentResolver
        val values = android.content.ContentValues()
        values.put(CalendarContract.Events.DTSTART, farFuture)
        values.put(CalendarContract.Events.TITLE, "Next week meeting")
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        resolver.insert(CalendarContract.Events.CONTENT_URI, values)

        val events = OrionCalendarMonitor.upcoming(context, 24, now)
        assertTrue(events.isEmpty())
    }

    @Test fun soonReturnsLabelledBlock() {
        OrionSensorConsent.grant(context, OrionSensorScope.CALENDAR_EVENTS, 4 * 60 * 60 * 1000L)
        val now = System.currentTimeMillis()
        val future = now + 45 * 60_000L // 45 min from now

        val resolver = context.contentResolver
        val values = android.content.ContentValues()
        values.put(CalendarContract.Events.DTSTART, future)
        values.put(CalendarContract.Events.TITLE, "Dentist appointment")
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        resolver.insert(CalendarContract.Events.CONTENT_URI, values)

        val block = OrionCalendarMonitor.soon(context, 24, now)
        assertNotNull(block)
        assertTrue(block.contains("OWNER-GRANTED CALENDAR CONTEXT"))
        assertTrue(block.contains("Dentist appointment"))
    }

    @Test fun lastNotifiedEventIdDefaultsToZero() {
        assertEquals(0L, OrionCalendarMonitor.lastNotifiedEventId(context))
    }

    @Test fun markEventNotifiedPersists() {
        OrionCalendarMonitor.markEventNotified(context, 42L)
        assertEquals(42L, OrionCalendarMonitor.lastNotifiedEventId(context))
    }
}
