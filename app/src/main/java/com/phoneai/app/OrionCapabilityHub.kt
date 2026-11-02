package com.phoneai.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Shared read-only capability snapshot used by the Watcher and diagnostics.
 * Every state is derived from the live tool registry, pack policy, Android permission state,
 * and connection metadata. It never treats a screen, saved key, or model name as proof of readiness.
 */
internal object OrionCapabilityHub {
    enum class State { READY, LIMITED, DISABLED, SETUP_REQUIRED, UNKNOWN }

    data class Capability(
        val name: String,
        val group: String,
        val state: State,
        val detail: String,
        val tools: List<String>
    )

    data class Snapshot(
        val checkedAt: Long,
        val registeredTools: Int,
        val availableTools: Int,
        val capabilities: List<Capability>,
        val integrationStates: Map<String, String>,
        val limitations: List<String>
    )

    private fun notificationAccessGranted(context: Context): Boolean = runCatching {
        val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        raw.split(':').any { it.substringBefore('/').equals(context.packageName, ignoreCase = true) }
    }.getOrDefault(false)

    private fun permissionStates(context: Context): Map<String, OrionCapabilityDiscovery.PermissionState> {
        val contacts = if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
            OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        val location = if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        val notifications = if (notificationAccessGranted(context))
            OrionCapabilityDiscovery.PermissionState.GRANTED else OrionCapabilityDiscovery.PermissionState.MISSING
        return mapOf("contacts" to contacts, "location" to location, "notification_access" to notifications)
    }

    private fun integrationStates(context: Context): Map<String, String> {
        val result = linkedMapOf<String, String>()
        runCatching {
            OrionConnectionStore.list(context).forEach { connection ->
                result["connection_${connection.providerId}"] = when {
                    !connection.secretAvailable -> "saved_without_secret"
                    !connection.verified -> "saved_unverified"
                    else -> "verified_metadata"
                }
            }
        }
        result["telegram"] = if (runCatching { TelegramBridge.isConfigured(context) }.getOrDefault(false)) "configured_metadata" else "not_configured"
        result["termux"] = if (runCatching { TermuxBridgeRunner.readiness(context) == null }.getOrDefault(false)) "ready" else "not_ready"
        return result
    }

    fun snapshot(context: Context, service: PhoneAiService? = PhoneAiService.instance): Snapshot {
        val packs = AgentPolicy.packs.filter { it.implemented && AgentPolicy.enabled(context, it.id) }.map { it.id }.toSet()
        val states = integrationStates(context)
        val discovery = OrionCapabilityDiscovery.discover(
            registeredTools = ToolRegistry.all,
            enabledPacks = packs,
            termuxReady = states["termux"] == "ready",
            permissionStates = permissionStates(context),
            integrationStates = states,
            deniedTools = ToolRegistry.all.map { it.name }
                .filter { it !in ToolPermissionPolicy.alwaysAllowed && ToolPermissions.mode(context, it) == ToolMode.DENY }
                .toSet()
        )
        val rootReadable = service != null && runCatching {
            val root = service.rootInActiveWindow
            val found = root != null
            root?.recycle()
            found
        }.getOrDefault(false)

        val grouped = discovery.tools.groupBy { fact ->
            when {
                fact.name.contains("github") || fact.name.contains("gmail") || fact.name.contains("drive") || fact.name.contains("mcp") -> "Connected services"
                fact.name.contains("screen") || fact.name.contains("click") || fact.name.contains("app") || fact.name.contains("notification") || fact.name.contains("media") -> "Phone control"
                fact.name.contains("browser") || fact.name.contains("web") || fact.name.contains("search") || fact.name.contains("research") -> "Browser & research"
                fact.name.contains("trading") || fact.name.contains("market") || fact.name.contains("price") || fact.name.contains("crypto") || fact.name.contains("forex") -> "Trading desk"
                fact.name.contains("memory") || fact.name.contains("lesson") || fact.name.contains("task") || fact.name.contains("skill") || fact.name.contains("extension") -> "Memory, tasks & skills"
                else -> "Core & other tools"
            }
        }
        val capabilities = grouped.map { (group, facts) ->
            val ready = facts.count { it.available }
            val detail = facts.filterNot { it.available }.take(4).joinToString("; ") { "${it.name}: ${it.state.name.lowercase()} — ${it.reason}" }
            val states = facts.map { it.state }.toSet()
            val groupState = when {
                states.all { it == CapabilityRegistry.State.AVAILABLE } -> State.READY
                facts.any { it.available } -> State.LIMITED
                states.all { it == CapabilityRegistry.State.DISABLED } -> State.DISABLED
                states.any { it == CapabilityRegistry.State.UNSUPPORTED } && states.size == 1 -> State.UNKNOWN
                states.any { it == CapabilityRegistry.State.PERMISSION_REQUIRED ||
                    it == CapabilityRegistry.State.SETUP_REQUIRED ||
                    it == CapabilityRegistry.State.MISSING_DEPENDENCY ||
                    it == CapabilityRegistry.State.TEMPORARILY_UNAVAILABLE ||
                    it == CapabilityRegistry.State.FAILED_HEALTH } -> State.SETUP_REQUIRED
                else -> State.UNKNOWN
            }
            Capability(
                name = group,
                group = group,
                state = groupState,
                detail = if (detail.isBlank()) "${ready}/${facts.size} registered tools available under current policy." else "${ready}/${facts.size} tools available. ${detail}",
                tools = facts.map { it.name }
            )
        }.toMutableList()

        if (discovery.tools.any { it.name == "read_screen" }) {
            capabilities.add(Capability(
                name = "Screen perception",
                group = "Phone control",
                state = if (rootReadable) State.READY else if (service == null) State.SETUP_REQUIRED else State.LIMITED,
                detail = if (rootReadable) "An active accessibility window was readable during this snapshot." else if (service == null)
                    "Accessibility service is not connected; enable it in Android settings, then recheck." else
                    "Service is connected, but no active accessibility root was readable now. This does not prove permission is missing.",
                tools = listOf("read_screen")
            ))
        }

        val limitations = buildList {
            addAll(discovery.blockers)
            addAll(capabilities.filter { it.state != State.READY }.map { "${it.name}: ${it.detail}" })
        }.distinct().take(24)
        return Snapshot(System.currentTimeMillis(), discovery.registeredCount, discovery.availableCount, capabilities, states, limitations)
    }

    fun stateLabel(state: State): String = when (state) {
        State.READY -> "READY"
        State.LIMITED -> "LIMITED"
        State.DISABLED -> "DISABLED"
        State.SETUP_REQUIRED -> "SETUP REQUIRED"
        State.UNKNOWN -> "UNKNOWN"
    }
}
