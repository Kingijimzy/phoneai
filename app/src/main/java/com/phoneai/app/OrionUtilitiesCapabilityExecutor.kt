package com.phoneai.app

import org.json.JSONObject

/**
 * Owns local Android utility operations: settings navigation, alarms/timers, media controls,
 * clipboard, battery/time/device facts and bounded waits. Shared policy, approval and evidence
 * recording remain in Agent before and after this executor.
 */
internal object OrionUtilitiesCapabilityExecutor {
    private val supportedTools = setOf(
        "settings", "alarm", "timer", "volume", "flashlight", "copy", "battery",
        "time", "wait", "device_info", "device_location"
    )

    fun supports(tool: String): Boolean = tool in supportedTools

    fun execute(tool: String, args: JSONObject, tools: Tools, stopped: () -> Boolean): String? {
        if (!supports(tool)) return null
        if (stopped()) return "stopped"

        return when (tool) {
            "settings" -> {
                val page = args.optString("page", "main").trim().lowercase()
                val allowed = setOf(
                    "main", "wifi", "bluetooth", "display", "sound", "battery", "apps",
                    "location", "accessibility", "airplane", "storage", "date", "data"
                )
                if (page !in allowed) "error: unsupported settings page '$page'"
                else tools.settings(page)
            }
            "alarm" -> {
                val hour = args.optInt("hour", 7)
                val minute = args.optInt("minute", 0)
                if (hour !in 0..23 || minute !in 0..59) {
                    "error: alarm hour must be 0-23 and minute must be 0-59"
                } else tools.alarm(hour, minute, args.optString("label", "Orion"))
            }
            "timer" -> {
                val seconds = args.optInt("seconds", 60)
                if (seconds !in 1..86400) "error: timer seconds must be between 1 and 86400"
                else tools.timer(seconds, args.optString("label", "Orion"))
            }
            "volume" -> tools.volume(args.optString("action", "up"), args.optInt("percent", 50))
            "flashlight" -> tools.flashlight(args.optBoolean("on", true))
            "copy" -> tools.copy(args.optString("text"))
            "battery" -> tools.battery()
            "time" -> tools.time()
            "device_info" -> tools.deviceInfo()
            "device_location" -> tools.deviceLocation()
            "wait" -> waitBounded(args, stopped)
            else -> null
        }
    }

    private fun waitBounded(args: JSONObject, stopped: () -> Boolean): String {
        val requested = args.optDouble("seconds", 2.0).takeIf { it.isFinite() } ?: 2.0
        val durationMs = (requested.coerceIn(0.5, 10.0) * 1000.0).toLong()
        val deadline = System.nanoTime() + durationMs * 1_000_000L
        while (true) {
            if (stopped()) return "stopped"
            val remainingNs = deadline - System.nanoTime()
            if (remainingNs <= 0L) return "waited"
            try {
                Thread.sleep(minOf(100L, (remainingNs / 1_000_000L).coerceAtLeast(1L)))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return "stopped"
            }
        }
    }
}
