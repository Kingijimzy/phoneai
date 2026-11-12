package com.phoneai.app

import org.json.JSONObject

/**
 * Owns Android-facing capability execution.
 *
 * Agent retains planning, cancellation, task evidence and recovery. This executor
 * owns the actual Android/app/accessibility/notification/media dispatch and receives only the
 * small Agent callbacks needed for higher-level verification flows.
 */
internal object OrionDeviceCapabilityExecutor {
    private val supportedTools = setOf(
        "lookup_app", "open_app", "system_intent", "open_deep_link", "read_notifications",
        "media_status", "media_control", "play_media", "chrome_search", "resolve_contact", "list_apps",
        "list_app_deeplinks", "read_screen", "validate_action", "verify_action", "verify_send",
        "click", "click_text", "point_at", "sketch_screen", "wait_for", "tap", "long_press", "swipe", "swipe_path",
        "scroll", "type", "key", "look", "play", "record_screen",
        "chronos_awareness", "calendar_events", "voice_speak", "voice_listen",
        "routine_run", "routine_resume", "device_operation"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(
        tool: String,
        args: JSONObject,
        service: PhoneAiService,
        tools: Tools,
        stopped: () -> Boolean,
        confirm: (String) -> Boolean,
        validateAction: (JSONObject) -> String,
        verifyAction: (JSONObject) -> String,
        verifySend: (JSONObject) -> String,
        look: (String) -> String,
        play: (String, Int) -> String,
        readScreen: () -> String,
        runRoutine: (String, String) -> String = { _, _ -> "error: saved routine replay callback unavailable" }
    ): String = when (tool) {
        "lookup_app" -> tools.lookupApp(args.optString("name"))
        "open_app" -> service.openApp(args.optString("name"))
        "system_intent" -> tools.systemIntent(args.optString("action"), args.optString("query"), args.optString("url"))
        "device_operation" -> DeviceOperationRouter.execute(service, args.optString("operation"), args.optString("value"))
        "open_deep_link" -> tools.openDeepLink(args.optString("uri"))
        "read_notifications" -> OrionNotificationListener.readSafeNotifications()
        "media_status" -> OrionNotificationListener.mediaStatus()
        "media_control" -> OrionNotificationListener.controlMedia(args.optString("action"))
        "play_media" -> tools.playMedia(args.optString("query"), args.optString("app").takeIf { it.isNotBlank() })
        "chrome_search" -> tools.chromeSearch(args.optString("query"))
        "resolve_contact" -> tools.resolveContact(args.optString("name"))
        "list_apps" -> tools.listApps()
        "list_app_deeplinks" -> tools.listAppDeepLinks(args.optString("scheme"))
        "read_screen" -> readScreen()
        "record_screen" -> if (args.optString("action", "start").equals("stop", true)) tools.recordStop() else tools.recordStart()
        "validate_action" -> validateAction(args)
        "verify_action" -> verifyAction(args)
        "verify_send" -> verifySend(args)
        "click" -> {
            val id = args.optInt("id", -1)
            if (id < 0 || service.nodeInfo(id) == null) "error: target ID is stale or missing. Run read_screen and choose a current ID."
            else service.clickId(id)
        }
        "click_text" -> tools.clickText(args.optString("text"))
        "point_at" -> service.pointAt(args.optInt("id", -1), args.optLong("duration_ms", 2400L))
        "sketch_screen" -> service.sketchScreen(
            id = args.optInt("id", -1),
            shape = args.optString("shape", "circle"),
            labelText = args.optString("label"),
            durationMs = args.optLong("duration_ms", 4200L),
            x = args.optInt("x", -1),
            y = args.optInt("y", -1),
            radius = args.optInt("radius", 48)
        )
        "wait_for" -> tools.waitFor(args.optString("text"), args.optDouble("seconds", 8.0))
        "tap" -> expectedPackageError(args, service) ?: withCoordinates(args, "x", "y") { x, y -> service.tap(x, y) }
        "long_press" -> expectedPackageError(args, service) ?: withCoordinates(args, "x", "y") { x, y -> service.longPress(x, y) }
        "swipe" -> {
            val mismatch = expectedPackageError(args, service)
            if (mismatch != null) return mismatch
            val x1 = number(args, "x1")
            val y1 = number(args, "y1")
            val x2 = number(args, "x2")
            val y2 = number(args, "y2")
            if (x1 == null || y1 == null || x2 == null || y2 == null) "error: swipe needs x1,y1,x2,y2"
            else service.swipe(x1, y1, x2, y2, args.optLong("ms", 300L))
        }
        "swipe_path" -> {
            val mismatch = expectedPackageError(args, service)
            if (mismatch != null) return mismatch
            val points = args.optJSONArray("points")
            if (points == null || points.length() < 2) "error: recorded swipe needs at least two points"
            else service.performRecordedPath(points, args.optLong("ms", 300L))
        }
        "scroll" -> expectedPackageError(args, service) ?: service.scroll(args.optString("direction", "down"))
        "type" -> expectedPackageError(args, service) ?: service.typeText(args.optString("text"))
        "key" -> service.key(args.optString("name"))
        "look" -> look(args.optString("question", "Describe what is on the screen."))
        "play" -> play(args.optString("goal"), args.optInt("steps", 20))
        "routine_run" -> runRoutine("run", args.optString("name"))
        "routine_resume" -> runRoutine("resume", args.optString("name"))
        "chronos_awareness" -> OrionChronosAwareness.summary(service).takeIf { it.isNotBlank() } ?: "Chronos context is unavailable without owner-granted app-usage and Chronos awareness consent."
        "calendar_events" -> {
            val lookahead = args.optInt("lookaheadHours", Prefs.calendarLookaheadHours(service))
            OrionCalendarMonitor.soon(service, lookahead).takeIf { it.isNotBlank() } ?: "No upcoming calendar events within the lookahead window (or calendar consent is not granted)."
        }
        "voice_speak" -> {
            val text = args.optString("text", "")
            if (text.isBlank()) "error: voice_speak requires non-empty text"
            else {
                val voice = Voice(service)
                voice.speak(text)
                "spoke ${text.length} character(s); verify delivery through the audio output"
            }
        }
        "voice_listen" -> {
            val seconds = args.optDouble("seconds", 8.0).coerceIn(0.5, 30.0)
            "voice_listen is handled through the live Voice activity; use the microphone UI to speak and read the transcript"
        }
        else -> "error: device executor does not implement '$tool'."
    }

    /** Optional foreground-app guard for actions planned from a prior screen observation. */
    private fun expectedPackageError(args: JSONObject, service: PhoneAiService): String? {
        val expected = args.optString("expected_package").trim()
        if (expected.isBlank()) return null
        val actual = service.currentPackageName()
        return if (actual.equals(expected, ignoreCase = true)) null
        else "error: foreground app changed (expected $expected, found ${actual.ifBlank { "no accessible window" }}); run read_screen again before acting"
    }

    private fun number(args: JSONObject, key: String): Float? {
        if (!args.has(key)) return null
        val value = args.optDouble(key, Double.NaN)
        return if (value.isNaN()) null else value.toFloat()
    }

    private fun withCoordinates(
        args: JSONObject,
        xKey: String,
        yKey: String,
        action: (Float, Float) -> String
    ): String {
        val x = number(args, xKey)
        val y = number(args, yKey)
        return if (x == null || y == null) "error: need $xKey and $yKey" else action(x, y)
    }
}
