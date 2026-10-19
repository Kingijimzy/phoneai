package com.phoneai.app

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

data class GameDeviceSnapshot(
    val batteryPercent: Int?,
    val batteryTemperatureC: Float?,
    val lowMemory: Boolean,
    val availableMemoryMb: Long,
    val totalMemoryMb: Long,
    val thermalStatus: String,
    val recommendation: String
)

data class GameSession(
    val id: Long,
    val appLabel: String,
    val packageName: String,
    val startedAt: Long,
    val endedAt: Long?,
    val durationSeconds: Long
)

/**
 * Lightweight, user-started gaming session tracker.
 * It does not watch other apps, use Accessibility, or claim to measure FPS/GPU temperature.
 */
object GameSentinel {
    private const val PREFS = "phoneai_game_sentinel"
    private const val KEY_HISTORY = "sessions"
    private const val KEY_ACTIVE = "active_session"

    fun deviceSnapshot(context: Context): GameDeviceSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val batteryTemp = try {
            val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.takeIf { it >= 0 }?.div(10f)
        } catch (_: Exception) { null }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29 && power != null) {
            thermalName(power.currentThermalStatus)
        } else "Not available on this Android version"
        val availableMb = memory.availMem / (1024 * 1024)
        val totalMb = memory.totalMem / (1024 * 1024)
        val recommendation = when {
            Build.VERSION.SDK_INT >= 29 && power != null && power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE ->
                "High thermal pressure: stop or pause the game and let the phone cool."
            memory.lowMemory || availableMb < 450 ->
                "Low available memory: close unused apps and avoid heavy background tasks."
            battery != null && battery < 15 ->
                "Battery is low: enable your preferred power mode or charge before a long session."
            batteryTemp != null && batteryTemp >= 43f ->
                "Battery temperature is high: pause gaming and allow the phone to cool."
            else -> "No immediate warning from the available device indicators."
        }
        return GameDeviceSnapshot(battery, batteryTemp, memory.lowMemory, availableMb, totalMb, thermal, recommendation)
    }

    @Synchronized
    fun startSession(context: Context, appLabel: String, packageName: String): GameSession? {
        val label = appLabel.trim().take(100)
        val pkg = packageName.trim().take(180)
        if (label.isBlank() || pkg.isBlank() || !pkg.matches(Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$"))) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ACTIVE, null) != null) return null
        val now = System.currentTimeMillis()
        val session = GameSession(now, label, pkg, now, null, 0L)
        val json = JSONObject().put("id", session.id).put("appLabel", label).put("packageName", pkg)
            .put("startedAt", now).put("endedAt", JSONObject.NULL)
        val saved = prefs.edit().putString(KEY_ACTIVE, json.toString()).commit()
        return if (saved) session else null
    }

    @Synchronized
    fun endSession(context: Context): GameSession? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = try { prefs.getString(KEY_ACTIVE, null)?.let(::JSONObject) } catch (_: Exception) { null }
            ?: return null
        val started = active.optLong("startedAt", 0L)
        val ended = System.currentTimeMillis()
        val session = GameSession(
            active.optLong("id", started),
            active.optString("appLabel", "Game"),
            active.optString("packageName", ""),
            started,
            ended,
            ((ended - started).coerceAtLeast(0L) / 1000L)
        )
        val history = readHistory(prefs)
        history.put(toJson(session))
        while (history.length() > 100) history.remove(0)
        val ok = prefs.edit().remove(KEY_ACTIVE).putString(KEY_HISTORY, history.toString()).commit()
        return if (ok) session else null
    }

    fun activeSession(context: Context): GameSession? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACTIVE, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val started = o.optLong("startedAt", 0L)
            GameSession(o.optLong("id", started), o.optString("appLabel", "Game"),
                o.optString("packageName", ""), started, null,
                ((System.currentTimeMillis() - started).coerceAtLeast(0L) / 1000L))
        } catch (_: Exception) { null }
    }

    fun history(context: Context): List<GameSession> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = readHistory(prefs)
        val items = (0 until arr.length()).mapNotNull { i ->
            try { fromJson(arr.getJSONObject(i)) } catch (_: Exception) { null }
        }.toMutableList()
        activeSession(context)?.let { items += it }
        return items.sortedByDescending { it.startedAt }
    }

    private fun readHistory(prefs: android.content.SharedPreferences): JSONArray =
        try { JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun toJson(s: GameSession) = JSONObject().put("id", s.id).put("appLabel", s.appLabel)
        .put("packageName", s.packageName).put("startedAt", s.startedAt)
        .put("endedAt", s.endedAt ?: JSONObject.NULL).put("durationSeconds", s.durationSeconds)

    private fun fromJson(o: JSONObject) = GameSession(
        o.optLong("id"), o.optString("appLabel"), o.optString("packageName"),
        o.optLong("startedAt"), if (o.isNull("endedAt")) null else o.optLong("endedAt"),
        o.optLong("durationSeconds")
    )

    private fun thermalName(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        else -> "Unknown"
    }
}
