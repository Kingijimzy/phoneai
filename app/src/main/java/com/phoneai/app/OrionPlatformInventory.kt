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
    data class Snapshot(
        val permissionStates: Map<String, OrionCapabilityDiscovery.PermissionState>,
        val lines: List<String>
    )

    fun read(context: Context): Snapshot {
        val app = context.applicationContext
        fun runtimePermission(manifest: String): OrionCapabilityDiscovery.PermissionState =
            runCatching {
                if (ContextCompat.checkSelfPermission(app, manifest) == PackageManager.PERMISSION_GRANTED)
                    OrionCapabilityDiscovery.PermissionState.GRANTED
                else OrionCapabilityDiscovery.PermissionState.MISSING
            }.getOrElse { OrionCapabilityDiscovery.PermissionState.UNKNOWN }

        fun specialAccess(key: String): OrionCapabilityDiscovery.PermissionState =
            runCatching {
                val enabled = Settings.Secure.getString(app.contentResolver, key)
                    ?: return@runCatching OrionCapabilityDiscovery.PermissionState.MISSING
                val matches = enabled.split(':').any { component ->
                    component.startsWith(app.packageName + "/") || component == app.packageName
                }
                if (matches) OrionCapabilityDiscovery.PermissionState.GRANTED
                else OrionCapabilityDiscovery.PermissionState.MISSING
            }.getOrElse { OrionCapabilityDiscovery.PermissionState.UNKNOWN }

        val fineLocation = runtimePermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = runtimePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val locationState = when {
            fineLocation == OrionCapabilityDiscovery.PermissionState.GRANTED ||
                coarseLocation == OrionCapabilityDiscovery.PermissionState.GRANTED -> OrionCapabilityDiscovery.PermissionState.GRANTED
            fineLocation == OrionCapabilityDiscovery.PermissionState.UNKNOWN ||
                coarseLocation == OrionCapabilityDiscovery.PermissionState.UNKNOWN -> OrionCapabilityDiscovery.PermissionState.UNKNOWN
            else -> OrionCapabilityDiscovery.PermissionState.MISSING
        }
        val permissions = linkedMapOf(
            "contacts" to runtimePermission(Manifest.permission.READ_CONTACTS),
            "location" to locationState,
            "camera" to runtimePermission(Manifest.permission.CAMERA),
            "microphone" to runtimePermission(Manifest.permission.RECORD_AUDIO),
            "notification_access" to specialAccess("enabled_notification_listeners"),
            "accessibility" to specialAccess("enabled_accessibility_services")
        )
        val labels = mapOf(
            "contacts" to "Contacts",
            "location" to "Location",
            "camera" to "Camera",
            "microphone" to "Microphone",
            "notification_access" to "Notification access",
            "accessibility" to "Accessibility service"
        )
        fun stateLabel(state: OrionCapabilityDiscovery.PermissionState) = when (state) {
            OrionCapabilityDiscovery.PermissionState.GRANTED -> "ON / granted"
            OrionCapabilityDiscovery.PermissionState.MISSING -> "OFF / not granted"
            OrionCapabilityDiscovery.PermissionState.UNKNOWN -> "UNKNOWN / could not verify"
        }
        val lines = mutableListOf<String>()
        permissions.forEach { (id, state) ->
            lines += "Permission ${labels[id] ?: id}: ${stateLabel(state)}."
        }

        val packs = runCatching {
            AgentPolicy.packs.associate { it.id to AgentPolicy.enabled(app, it.id) }
        }.getOrNull()
        if (packs == null) lines += "Capability packs: UNKNOWN; owner settings could not be read."
        else lines += "Capability packs: " + AgentPolicy.packs.joinToString("; ") {
            "${it.title} [${it.id}]=${if (packs[it.id] == true) "ON" else "OFF"}"
        } + "."

        val skills = runCatching { SkillRegistry.list(app) }.getOrNull()
        if (skills == null) lines += "Skills: UNKNOWN; local registry could not be read."
        else {
            val registeredTools = runCatching { ToolRegistry.names().toSet() }.getOrNull()
            lines += "Skills: " + skills.joinToString("; ") {
                "${it.id}=${if (it.enabled) "ON" else "OFF"} (tools=${it.allowedTools.size})"
            }.ifBlank { "none registered" } + "."
            if (registeredTools == null) lines += "Skill integrity: UNKNOWN; registered tool names could not be read."
            else {
                val broken = skills.filter { it.enabled && it.allowedTools.any { name -> name !in registeredTools } }
                if (broken.isNotEmpty()) lines += "Skill blockers: " + broken.joinToString { "${it.id} references unregistered tools" } + "."
            }
        }

        val watch = runCatching { Prefs.watchOn(app) }.getOrNull()
        lines += "Market WatchService owner switch: ${watch?.let { if (it) "ON" else "OFF" } ?: "UNKNOWN"}; process health is not independently verified."
        val core = packs?.get("orion_core")
        val proactive = packs?.get("proactive")
        val workState = runCatching {
            val infos = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork("orion_stage6_proactive_heartbeat").get(2, TimeUnit.SECONDS)
            when {
                infos.isEmpty() -> "NOT SCHEDULED"
                else -> infos.first().state.name
            }
        }.getOrElse { "UNKNOWN (${it.javaClass.simpleName})" }
        lines += "Proactive worker: core=${core?.let { if (it) "ON" else "OFF" } ?: "UNKNOWN"}, proactive pack=${proactive?.let { if (it) "ON" else "OFF" } ?: "UNKNOWN"}, WorkManager=$workState. Android may defer scheduled work."
        lines += "PERMISSION REQUEST BEHAVIOUR: before a task, inspect its required permission. If OFF, explain the exact permission and purpose, ask the owner to grant it, and wait. If UNKNOWN, say it cannot be verified and do not treat it as granted. Never claim permission was granted or try to bypass Android settings."
        lines += "STATE HONESTY: owner switches and WorkManager registration are not proof a service process is alive. Provider credentials require verification and successful requests. Label unobservable runtime states UNKNOWN."
        return Snapshot(permissions, lines)
    }
}
