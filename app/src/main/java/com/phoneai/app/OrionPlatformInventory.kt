package com.phoneai.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Read-only inventory of owner-controlled platform state. This is sampled on each chat turn;
 * it never grants permissions or starts services. Unknown is reported as unknown, never ON.
 */
internal object OrionPlatformInventory {
    data class Snapshot(
        val permissionStates: Map<String, OrionCapabilityDiscovery.PermissionState>,
        val lines: List<String>
    )

    fun read(context: Context): Snapshot {
        val app = context.applicationContext
        fun runtimePermission(name: String, manifest: String): OrionCapabilityDiscovery.PermissionState =
            if (ContextCompat.checkSelfPermission(app, manifest) == PackageManager.PERMISSION_GRANTED)
                OrionCapabilityDiscovery.PermissionState.GRANTED
            else OrionCapabilityDiscovery.PermissionState.MISSING

        fun secureEnabled(key: String): Boolean = runCatching {
            Settings.Secure.getString(app.contentResolver, key).orEmpty()
                .split(':').any { it.startsWith(app.packageName + "/") || it == app.packageName }
        }.getOrDefault(false)

        val accessibilityEnabled = secureEnabled("enabled_accessibility_services")
        val notificationEnabled = secureEnabled("enabled_notification_listeners")
        val permissions = linkedMapOf(
            "contacts" to runtimePermission("contacts", Manifest.permission.READ_CONTACTS),
            "location" to runtimePermission("location", Manifest.permission.ACCESS_FINE_LOCATION),
            "camera" to runtimePermission("camera", Manifest.permission.CAMERA),
            "microphone" to runtimePermission("microphone", Manifest.permission.RECORD_AUDIO),
            "notification_access" to if (notificationEnabled) OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING,
            "accessibility" to if (accessibilityEnabled) OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        )

        val lines = mutableListOf<String>()
        fun permissionLabel(key: String, label: String) {
            val state = permissions[key] ?: OrionCapabilityDiscovery.PermissionState.UNKNOWN
            val value = when (state) {
                OrionCapabilityDiscovery.PermissionState.GRANTED -> "ON / granted"
                OrionCapabilityDiscovery.PermissionState.MISSING -> "OFF / not granted"
                OrionCapabilityDiscovery.PermissionState.UNKNOWN -> "UNKNOWN / not verified"
            }
            lines += "Permission $label: $value."
        }
        permissionLabel("contacts", "Contacts")
        permissionLabel("location", "Location")
        permissionLabel("camera", "Camera")
        permissionLabel("microphone", "Microphone")
        permissionLabel("notification_access", "Notification access")
        permissionLabel("accessibility", "Accessibility service")

        val packs = AgentPolicy.packs.associate { it.id to AgentPolicy.enabled(app, it.id) }
        lines += "Capability packs: " + AgentPolicy.packs.joinToString("; ") {
            "${it.title} [${it.id}]=${if (packs[it.id] == true) "ON" else "OFF"}"
        } + "."

        val skills = runCatching { SkillRegistry.list(app) }.getOrNull()
        if (skills == null) {
            lines += "Skills: UNKNOWN; local skill registry could not be read."
        } else {
            lines += "Skills: " + (skills.joinToString("; ") {
                "${it.id}=${if (it.enabled) "ON" else "OFF"} (tools: ${it.allowedTools.size})"
            }.ifBlank { "none registered" }) + "."
            val broken = skills.filter { it.enabled }.filter { skill -> skill.allowedTools.any { ToolRegistry.get(it) == null } }
            if (broken.isNotEmpty()) lines += "Skill blockers: " + broken.joinToString { "${it.id} references missing tools" } + "."
        }

        val watchOn = runCatching { Prefs.watchOn(app) }.getOrDefault(false)
        lines += "Market WatchService owner switch: ${if (watchOn) "ON" else "OFF"} (foreground service process health is not independently verified here)."
        val coreOn = packs["orion_core"] == true
        val proactiveOn = packs["proactive"] == true
        val scheduled = runCatching {
            val infos = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork("orion_stage6_proactive_heartbeat")
                .get(2, TimeUnit.SECONDS)
            when {
                infos.isEmpty() -> "NOT SCHEDULED"
                infos.any { it.state.isFinished } -> infos.first().state.name
                else -> infos.first().state.name
            }
        }.getOrElse { "UNKNOWN (${it.javaClass.simpleName})" }
        lines += "Proactive worker: core=${if (coreOn) "ON" else "OFF"}, proactive pack=${if (proactiveOn) "ON" else "OFF"}, WorkManager state=$scheduled. Android may defer scheduled work."

        lines += "Permission request rule: if a required permission is OFF, tell the owner the exact permission and why it is needed, then ask them to grant it in Android settings or the app's permission prompt. Do not claim it is granted and do not repeatedly ask if already granted."
        lines += "State accuracy: these are sampled settings and registry states. A foreground service being enabled does not prove its process is alive; a connected account still needs verified credentials and a successful request; unsupported or unobservable states must be labelled UNKNOWN."
        return Snapshot(permissions, lines)
    }
}
