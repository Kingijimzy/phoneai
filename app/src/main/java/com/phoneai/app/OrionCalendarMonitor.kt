package com.phoneai.app

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import java.util.Locale

/**
 * Reads the owner's upcoming personal calendar events on demand.
 *
 * Only runs when OrionSensorScope.CALENDAR_EVENTS is active and Android's
 * READ_CALENDAR permission is granted. Returns a short, labelled block of
 * events starting within the lookahead window — never the full calendar,
 * never raw attendee lists, never descriptions beyond a short summary.
 */
internal object OrionCalendarMonitor {
    private const val PREFS = "orion_calendar_monitor_v1"
    private const val KEY_LAST_NOTIFIED = "last_notified_event_id"

    /** Upcoming event, enough context to act on without exposing the full calendar. */
    data class UpcomingEvent(
        val id: Long,
        val title: String,
        val startsAtMillis: Long,
        val location: String? = null,
        val minutesFromNow: Long = 0L
    )

    fun isEnabled(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        OrionSensorConsent.read(context, OrionSensorScope.CALENDAR_EVENTS, now).active()

    /** Returns events starting within the lookahead window, sorted by start time. */
    fun upcoming(
        context: Context,
        lookaheadHours: Int,
        now: Long = System.currentTimeMillis()
    ): List<UpcomingEvent> {
        if (!isEnabled(context, now)) return emptyList()
        val resolver: ContentResolver = context.applicationContext.contentResolver
        val start = now
        val end = now + lookaheadHours.coerceAtLeast(1).coerceAtMost(168).toLong() * 60 * 60 * 1000
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.EVENT_TIMEZONE} IS NOT NULL"
        val run = runCatching {
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                arrayOf(start.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )
        }.getOrNull()
        return run?.use { cursor ->
            val out = ArrayList<UpcomingEvent>()
            val idCol = cursor.getColumnIndex(CalendarContract.Events._ID)
            val titleCol = cursor.getColumnIndex(CalendarContract.Events.TITLE)
            val startCol = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
            val locCol = cursor.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
            while (cursor.moveToNext()) {
                val id = if (idCol >= 0) cursor.getLong(idCol) else 0L
                val title = if (titleCol >= 0) cursor.getString(titleCol) ?: "(no title)" else "(no title)"
                val startsAt = if (startCol >= 0) cursor.getLong(startCol) else now
                val location = if (locCol >= 0) cursor.getString(locCol) else null
                if (startsAt >= start && startsAt < end) {
                    out += UpcomingEvent(
                        id = id,
                        title = title.take(120),
                        startsAtMillis = startsAt,
                        location = location?.take(120),
                        minutesFromNow = (startsAt - now).coerceAtLeast(0L) / 60_000L
                    )
                }
            }
            out
        } ?: emptyList()
    }

    /** Events starting within the next hour, formatted as a short labelled block. */
    fun soon(
        context: Context,
        lookaheadHours: Int,
        now: Long = System.currentTimeMillis()
    ): String {
        val events = upcoming(context, lookaheadHours, now)
            .filter { it.minutesFromNow <= 120 } // within 2 hours
            .take(5)
        if (events.isEmpty()) return ""
        return buildString {
            appendLine("OWNER-GRANTED CALENDAR CONTEXT (upcoming personal events; read on demand, not stored):")
            events.forEach { e ->
                val startsIn = if (e.minutesFromNow < 60) "in ${e.minutesFromNow} min"
                else "in about ${e.minutesFromNow / 60} hours"
                appendLine("- ${e.title} $startsIn" + (if (e.location.isNullOrBlank()) "" else " @ ${e.location}"))
            }
            appendLine("Calendar events are read only when this turn is answered; no calendar data is persisted by Orion.")
        }.take(900)
    }

    /** The last event id Orion already spoke about, to avoid repeating the same reminder. */
    fun lastNotifiedEventId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_NOTIFIED, 0L)

    fun markEventNotified(context: Context, eventId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_NOTIFIED, eventId).apply()
    }
}
