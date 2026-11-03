package com.phoneai.app

import org.json.JSONObject

/**
 * Owns Android-facing capability execution.
 *
 * Agent retains planning, live policy/approval gates, task evidence and recovery. This executor
 * owns the actual Android/app/accessibility/notification/media dispatch and receives only the
 * small Agent callbacks needed for higher-level verification flows.
 */
internal object OrionDeviceCapabilityExecutor {
    private val supportedTools = setOf(
        "lookup_app", "open_app", "system_intent", "open_deep_link", "read_notifications",
        "notification_action", "media_status", "media_control", "resolve_contact", "list_apps",
        "list_app_deeplinks", "read_screen", "validate_action", "verify_action", "verify_send",
        "click", "click_text", "wait_for", "tap", "long_press", "swipe", "swipe_path",
        "scroll", "type", "key", "look", "play", "record_screen"
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
        readScreen: () -> String
    ): String = when (tool) {
        "lookup_app" -> tools.lookupApp(args.optString("name"))
        "open_app" -> service.openApp(args.optString("name"))
        "system_intent" -> tools.systemIntent(args.optString("action"), args.optString("query"), args.optString("url"))
        "open_deep_link" -> tools.openDeepLink(args.optString("uri"))
        "read_notifications" -> OrionNotificationListener.readSafeNotifications()
        "notification_action" -> {
            val action = args.optString("action").trim().lowercase()
            val key = args.optString("key")
            val reply = args.optString("text")
            when {
                action !in setOf("reply", "dismiss") -> "error: action must be reply or dismiss"
                action == "reply" && reply.isBlank() -> "error: reply text cannot be blank"
                stopped() -> "error: task was stopped before notification action; no action was dispatched"
                action == "reply" && !confirm("Reply to the selected notification with this exact text? " + reply.take(500)) ->
                    "error: user cancelled notification action"
                action == "dismiss" && !confirm("Dismiss the selected safe notification? This removes it from the notification shade.") ->
                    "error: user cancelled notification action"
                stopped() -> "error: task was stopped before notification action; no action was dispatched"
                else -> OrionNotificationListener.notificationAction(key, action, reply)
            }
        }
        "media_status" -> OrionNotificationListener.mediaStatus()
        "media_control" -> OrionNotificationListener.controlMedia(args.optString("action"))
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
        "wait_for" -> tools.waitFor(args.optString("text"), args.optDouble("seconds", 8.0))
        "tap" -> withCoordinates(args, "x", "y") { x, y -> service.tap(x, y) }
        "long_press" -> withCoordinates(args, "x", "y") { x, y -> service.longPress(x, y) }
        "swipe" -> {
            val x1 = number(args, "x1")
            val y1 = number(args, "y1")
            val x2 = number(args, "x2")
            val y2 = number(args, "y2")
            if (x1 == null || y1 == null || x2 == null || y2 == null) "error: swipe needs x1,y1,x2,y2"
            else service.swipe(x1, y1, x2, y2, args.optLong("ms", 300L))
        }
        "swipe_path" -> {
            val points = args.optJSONArray("points")
            if (points == null || points.length() < 2) "error: recorded swipe needs at least two points"
            else service.performRecordedPath(points, args.optLong("ms", 300L))
        }
        "scroll" -> service.scroll(args.optString("direction", "down"))
        "type" -> service.typeText(args.optString("text"))
        "key" -> service.key(args.optString("name"))
        "look" -> look(args.optString("question", "Describe what is on the screen."))
        "play" -> play(args.optString("goal"), args.optInt("steps", 20))
        else -> "error: device executor does not implement '$tool'."
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
