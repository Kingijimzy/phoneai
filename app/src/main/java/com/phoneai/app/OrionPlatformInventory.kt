package com.phoneai.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Read-only owner permission, skill, pack and worker inventory sampled on every chat turn. */
internal object OrionPlatformInventory {
    data class Snapshot(val permissionStates: Map<String, OrionCapabilityDiscovery.PermissionState>, val lines: List<String>)

    fun read(context: Context): Snapshot {
        val app = context.applicationContext
        fun runtimePermission(manifest: String) =
            if (ContextCompat.checkSelfPermission(app, manifest) == PackageManager.PERMISSION_GRANTED)
                OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        fun secureEnabled(key: String) = runCatching {
            Settings.Secure.getString(app.contentResolver, key).orEmpty().split(':')
                .any { it.startsWith(app.packageName + "/") || it == app.packageName }
        }.getOrDefault(false)

        val accessibility = secureEnabled("enabled_accessibility_services")
        val notifications = secureEnabled("enabled_notification_listeners")
        val permissions = linkedMapOf(
            "contacts" to runtimePermission(Manifest.permission.READ_CONTACTS),
            "location" to runtimePermission(Manifest.permission.ACCESS_FINE_LOCATION),
            "camera" to runtimePermission(Manifest.permission.CAMERA),
            "microphone" to runtimePermission(Manifest.permission.RECORD_AUDIO),
            "notification_access" to if (notifications) OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING,
            "accessibility" to if (accessibility) OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        )
        val lines = mutableListOf<String>()
        permissions.forEach { (id, state) ->
            val label = when (id) {
                "contacts" -> "Contacts"
                "location" -> "Location"
                "camera" -> "Camera"
                "microphone" -> "Microphone"
                "notification_access" -> "Notification access"
                else -> "Accessibility service"
            }
            lines += "Permission $label: ${if (state == OrionCapabilityDiscovery.PermissionState.GRANTED) "ON / granted" else "OFF / not granted"}."
        }

        val packs = AgentPolicy.packs.associate { it.id to AgentPolicy.enabled(app, it.id) }
        lines += "Capability packs: " + AgentPolicy.packs.joinToString("; ") {
            "${it.title} [${it.id}]=${if (packs[it.id] == true) "ON" else "OFF"}"
        } + "."

        val skills = runCatching { SkillRegistry.list(app) }.getOrNull()
        if (skills == null) lines += "Skills: UNKNOWN; local registry could not be read."
        else {
            val registeredTools = runCatching { ToolRegistry.names().toSet() }.getOrDefault(emptySet())
            lines += "Skills: " + skills.joinToString("; ") {
                "${it.id}=${if (it.enabled) "ON" else "OFF"} (tools=${it.allowedTools.size})"
            }.ifBlank { "none registered" } + "."
            val broken = skills.filter { it.enabled && it.allowedTools.any { name -> name !in registeredTools } }
            if (broken.isNotEmpty()) lines += "Skill blockers: " + broken.joinToString { "${it.id} references unregistered tools" } + "."
        }

        val watch = runCatching { Prefs.watchOn(app) }.getOrDefault(false)
        lines += "Market WatchService switch: ${if (watch) "ON" else "OFF"}; process health is not independently verified."
        val core = packs["orion_core"] == true
        val proactive = packs["proactive"] == true
        val workState = runCatching {
            val infos = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork("orion_stage6_proactive_heartbeat").get(2, TimeUnit.SECONDS)
            when {
                infos.isEmpty() -> "NOT SCHEDULED"
                else -> infos.first().state.name
            }
        }.getOrElse { "UNKNOWN (${it.javaClass.simpleName})" }
        lines += "Proactive worker: core=${if (core) "ON" else "OFF"}, proactive pack=${if (proactive) "ON" else "OFF"}, WorkManager=$workState. Android may defer scheduled work."
        lines += "PERMISSION REQUEST BEHAVIOUR: before a task, inspect its required permission. If OFF, explain the exact permission and purpose, ask the owner to grant it, and wait. Never claim it is granted or try to bypass Android settings. If UNKNOWN, say it cannot be verified and do not treat it as granted."
        lines += "STATE HONESTY: owner switches and WorkManager registration are not proof a service process is alive. Provider credentials require verification and successful requests. Label unobservable runtime states UNKNOWN."
        return Snapshot(permissions, lines)
    }
}
