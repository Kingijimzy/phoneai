package com.phoneai.app

import android.content.Context
import org.json.JSONObject

/**
 * The MacGyver hierarchy behind execute_system_op. One master tool: the model writes a command and (optionally) a route,
 * the router picks the strongest fit and falls down the chain on failure.
 *   1. SHIZUKU  (ADB-level, system toggles, app management)
 *   2. LINUX    (PRoot/Alpine shell: conversion, downloads, git, servers)
 *   3. PYTHON   (logic: scraping, APIs, parsing)
 *   4. ACCESSIBILITY (visual fallback when ADB is blocked or fails)
 */
internal object OrionSystemOps {
    enum class Route { SHIZUKU, LINUX, PYTHON, ACCESSIBILITY }

    private val shizukuHead = Regex("""^(adb\s+shell\s+)?(cmd|settings|pm|am|dumpsys|svc|input|wm|appops|device_config|content|monkey|getprop|setprop|screencap|uiautomator|telecom|locksettings|bmgr|dpm|ime|media|logcat|service|pidof|ps|top|df|netstat|ip|ifconfig|reboot|stop|start)\b""", RegexOption.IGNORE_CASE)
    private val linuxHead = Regex("""(^|[\s;&|(])(ffmpeg|ffprobe|git|curl|wget|tar|unzip|zip|7z|apk|make|gcc|g\+\+|cargo|go|node|npm|pnpm|yarn|ssh|scp|rsync|yt-dlp|youtube-dl|pip3?|convert|magick|sox|pandoc|sqlite3|jq|nginx|caddy|http\.server|openssl|nmap|ping|dig)\b""", RegexOption.IGNORE_CASE)
    private val pythonLook = Regex("""^(python3?:|py:)|^\s*(import\s+\w+|from\s+\w+\s+import|def\s+\w+\(|print\(|requests\.|with\s+open\()""", RegexOption.MULTILINE)

    fun classify(command: String): Route {
        val c = command.trim()
        if (pythonLook.containsMatchIn(c)) return Route.PYTHON
        if (shizukuHead.containsMatchIn(c)) return Route.SHIZUKU
        if (linuxHead.containsMatchIn(c) || c.contains('\n') || c.startsWith("#!")) return Route.LINUX
        return Route.LINUX
    }

    fun parseRoute(raw: String?): Route? = when (raw.orEmpty().trim().lowercase()) {
        "shizuku", "adb", "native", "android" -> Route.SHIZUKU
        "linux", "proot", "shell", "bash", "alpine" -> Route.LINUX
        "python", "py", "sandbox" -> Route.PYTHON
        "accessibility", "ui", "visual", "screen" -> Route.ACCESSIBILITY
        else -> null
    }

    /** Ordered attempts. A forced route is tried first, then the rest of the hierarchy that can run this kind of command. */
    fun chain(command: String, requested: Route?): List<Route> {
        val primary = requested ?: classify(command)
        val shellLike = primary != Route.PYTHON
        val out = ArrayList<Route>()
        out += primary
        if (shellLike) {
            if (Route.SHIZUKU !in out) out += Route.SHIZUKU
            if (Route.LINUX !in out) out += Route.LINUX
        }
        if (primary == Route.PYTHON && Route.LINUX !in out) out += Route.LINUX
        if (Route.ACCESSIBILITY !in out) out += Route.ACCESSIBILITY
        return out.distinct()
    }

    fun stripAdb(command: String): String = command.trim().replace(Regex("^adb\\s+shell\\s+", RegexOption.IGNORE_CASE), "")

    fun failed(out: String): Boolean {
        val t = out.trim()
        if (t.isEmpty()) return false
        val head = t.take(300).lowercase()
        return head.startsWith("error") || OrionReflexion.looksFailed(t) && !head.contains("exit=0")
    }

    fun accessibilityGuidance(command: String): String =
        "ACCESSIBILITY FALLBACK: no command route could complete this, so do it visually now. Open the right screen (open_app, or key/home; Settings for toggles), " +
            "then use look/read screen, click_text, tap, type and verify_action until the end state is confirmed. Goal from the failed command: \"${command.take(300)}\"."

    fun run(c: Context, tools: Tools, pythonExecutor: OrionPythonCapabilityExecutor?, args: JSONObject, stopped: () -> Boolean, confirm: (String) -> Boolean): String {
        val command = args.optString("command").ifBlank { args.optString("script") }
        if (command.isBlank()) return "error: command is required. execute_system_op {command, tool_route: auto|shizuku|linux|python|accessibility}"
        val requested = parseRoute(args.optString("tool_route").ifBlank { args.optString("route") })
        val chain = chain(command, requested)
        val log = ArrayList<String>()
        for (route in chain) {
            if (stopped()) return "Stopped."
            val out: String = when (route) {
                Route.SHIZUKU -> OrionShizukuBridge.execute(c, "exec", stripAdb(command))
                Route.LINUX -> tools.shell(stripAdb(command), "linux")
                Route.PYTHON -> pythonExecutor?.execute("python_exec", JSONObject().put("code", command.removePrefix("python:").removePrefix("python3:").removePrefix("py:").trim()), confirm, stopped)
                    ?: "error: python executor unavailable"
                Route.ACCESSIBILITY -> { log += "accessibility → handing over"; return "ROUTE: ${log.joinToString(" | ")}\n" + accessibilityGuidance(command) }
            }
            if (!failed(out)) return "ROUTE: ${(log + "${route.name.lowercase()} ✓").joinToString(" → ")}\n$out"
            log += "${route.name.lowercase()} ✗ (${out.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(90)})"
        }
        return "ROUTE: ${log.joinToString(" → ")}\nerror: every route failed."
    }
}
