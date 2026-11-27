package com.phoneai.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The world model: a live, structured WorldState read from the running device and runtime, not described in prose.
 * It is injected as JSON into every prompt and shown verbatim in the Environment pill, so the owner sees exactly
 * what Orion sees. A field that cannot be read is listed under "unreadable" instead of being guessed.
 */
internal object OrionWorldState {
    /** Pure assembler so the shape can be tested without Android. [parts] values may be JSONObject/primitive/null (null = unreadable). */
    internal fun assemble(parts: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        val unreadable = JSONArray()
        for ((k, v) in parts) if (v == null) unreadable.put(k) else o.put(k, v)
        return o.put("unreadable", unreadable)
    }

    internal fun partOfDay(hour: Int) = when (hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }

    private fun <T> read(block: () -> T): T? = runCatching(block).getOrNull()

    fun build(c: Context, now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()), request: String = ""): JSONObject {
        val observedAt = now.toInstant().toEpochMilli()
        val time = read { JSONObject().put("iso", now.toString().take(25)).put("zone", now.zone.id).put("part_of_day", partOfDay(now.hour)).put("observed_at_ms", observedAt) }
        val battery = read {
            val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))!!
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            JSONObject().put("percent", if (level >= 0) level * 100 / scale else JSONObject.NULL)
                .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                .put("observed_at_ms", observedAt)
        }
        val network = read {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "online"
            }
        }
        val agents = read {
            JSONObject()
                .put("cerebro", OrionCerebro.status(c).lineSequence().first().take(110))
                .put("chronos", OrionChronos.status(c).take(110))
                .put("prometheus", OrionPrometheus.status(c).take(130))
                .put("workers", JSONObject().put("running", OrionWorkers.runningCount()).put("max", OrionWorkers.MAX_PARALLEL))
        }
        val missions = read {
            JSONObject().put("active", OrionMissionControl.active().size)
                .put("objective", request.takeIf { it.isNotBlank() }?.let { OrionMissionLock.goalFor(c, it) }.orEmpty().take(100))
        }
        val tools = read {
            val names = ToolRegistry.names()
            JSONObject().put("registered", names.size).put("implemented", names.count { ToolRegistry.isImplemented(it) })
        }
        val providers = read {
            val all = Providers.all
            JSONObject().put("configured", all.count { Prefs.hasKey(c, it.id) || it.noKey })
                .put("primary", Nim.lastChatModel().ifBlank { "not used yet this session" })
        }
        val permissions = read {
            JSONObject().put("microphone", ContextCompat.checkSelfPermission(c, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                .put("accessibility", PhoneAiService.instance != null)
        }
        return assemble(linkedMapOf(
            "snapshot_observed_at_ms" to observedAt, "network_observed_at_ms" to if (network != null) observedAt else null,
            "time" to time, "battery" to battery, "network" to network, "agents" to agents, "missions" to missions,
            "tools" to tools, "providers" to providers, "permissions" to permissions
        ))
    }

    fun json(c: Context): String = runCatching { build(c).toString() }.getOrDefault("{\"unreadable\":[\"world_state\"]}")

    fun pretty(c: Context): String = runCatching { build(c).toString(2) }.getOrDefault("World state could not be read.")

    /** The prompt section: the JSON itself, plus how to treat it. */
    fun section(c: Context, request: String = ""): String =
        "WORLD STATE (live JSON read from the device this turn; trust it over memory, and say so when a field is unreadable):\n" + runCatching { build(c, request = request).toString() }.getOrDefault("{\"unreadable\":[\"world_state\"]}") +
            "\nROUTES (ranked from measured outcomes this session; pick the top route for the kind of work, switch to the next when one fails; never switch the owner's screen to recover):\n" +
            runCatching { OrionStrategy.summary(build(c)) }.getOrDefault("unavailable") + "\n" + OrionTeam.summary(c) + "\n" + OrionForesight.routeRecord() + "\n" + runCatching { OrionJobLedger.current(c) }.getOrNull()?.let { OrionJobLedger.format(it) }.orEmpty()
}
