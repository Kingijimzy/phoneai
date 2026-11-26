package com.phoneai.app

import org.json.JSONObject

/**
 * Owns local Android utility operations: settings navigation, alarms/timers, media controls,
 * clipboard, battery/time/device facts and bounded waits. Shared policy, approval and evidence
 * recording remain in Agent before and after this executor.
 */
internal object OrionUtilitiesCapabilityExecutor {
    private val supportedTools = setOf(
        "settings", "alarm", "timer", "volume", "flashlight", "copy", "clipboard_read", "self_edit", "brain", "mesh", "a2a", "plugin", "web_api", "fact_add", "fact_query", "fact_forget", "show_file", "present_chart", "call_status", "call_place", "call_answer", "call_end", "battery",
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
            "clipboard_read" -> tools.clipboardRead()
            "self_edit" -> tools.selfEdit(args)
            "brain" -> tools.brain(args)
            "mesh" -> tools.mesh(args)
            "a2a" -> OrionA2A.run(args)
            "plugin" -> tools.plugin(args)
            "web_api" -> tools.webApi(args)
            "fact_add", "fact_query", "fact_forget" -> tools.factTool(tool, args)
            "show_file" -> tools.showFile(args.optString("path"))
            "present_chart" -> presentChart(args)
            "call_status" -> tools.callStatus()
            "call_place" -> tools.callPlace(args.optString("number"))
            "call_answer" -> tools.callAnswer()
            "call_end" -> tools.callEnd()
            "battery" -> tools.battery()
            "time" -> tools.time()
            "device_info" -> tools.deviceInfo()
            "device_location" -> tools.deviceLocation()
            "wait" -> waitBounded(args, stopped)
            else -> null
        }
    }

    /**
     * Builds the chart payload activityNote() wraps as "[CHART]\n<json>" so OrionChatComponents
     * can render it inline instead of making the model describe numbers as prose. Validated here
     * (not just in the prompt contract) so a malformed call fails with a clear reason the model
     * can act on, instead of silently rendering an empty or mismatched chart.
     */
    private fun presentChart(args: JSONObject): String {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return "error: present_chart needs a title"
        val kind = args.optString("kind", "bar").lowercase()
        if (kind != "bar" && kind != "line") return "error: present_chart kind must be 'bar' or 'line'"
        val labelsArr = args.optJSONArray("labels")
        val valuesArr = args.optJSONArray("values")
        if (labelsArr == null || valuesArr == null) return "error: present_chart needs labels and values arrays"
        if (labelsArr.length() != valuesArr.length()) return "error: present_chart labels and values must be the same length"
        if (labelsArr.length() < 2 || labelsArr.length() > 12) return "error: present_chart needs 2 to 12 points, got ${labelsArr.length()}"
        val labels = (0 until labelsArr.length()).map { labelsArr.optString(it).take(24) }
        val values = (0 until valuesArr.length()).map { valuesArr.optDouble(it, 0.0) }
        if (values.any { !it.isFinite() }) return "error: present_chart values must be finite numbers"
        val payload = JSONObject()
            .put("title", title.take(80))
            .put("kind", kind)
            .put("labels", org.json.JSONArray(labels))
            .put("values", org.json.JSONArray(values))
            .put("unit", args.optString("unit").take(16))
        return payload.toString()
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
