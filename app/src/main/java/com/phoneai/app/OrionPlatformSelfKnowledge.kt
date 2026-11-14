package com.phoneai.app

import android.content.Context
import android.os.Build

/**
 * Runtime-derived map of the Android device and Orion platform.
 *
 * This is intentionally generated from the live tool registry, adapter routes, owner settings,
 * permission state and connected-service observations. It is a model-facing map, not an alternate
 * permission system and not a promise that every registered backend is currently healthy.
 */
internal object OrionPlatformSelfKnowledge {
    fun capabilityLayerMap(
        registeredTools: Set<String>,
        routes: List<OrionCapabilityAdapters.Route>
    ): String = buildString {
        appendLine("ORION CAPABILITY LAYERS — LIVE REGISTRY MAP")
        val routed = routes.groupBy { it.backend }
        OrionCapabilityAdapters.Backend.values().forEach { backend ->
            val names = routed[backend].orEmpty().map { it.tool }.distinct().sorted()
            if (names.isNotEmpty()) {
                appendLine("- ${backend.name.lowercase()}: ${names.joinToString(", ")}")
            }
        }
        val routedNames = routes.map { it.tool }.toSet()
        val unclassified = (registeredTools - routedNames).sorted()
        if (unclassified.isNotEmpty()) {
            appendLine("- registered tools without an explicit capability-family adapter (legacy/direct route; inspect their registered contract): ${unclassified.joinToString(", ")}")
        }
        appendLine("Coverage: registered_tools=${registeredTools.size}; adapter_routed=${routedNames.size}; unclassified=${unclassified.size}.")
    }

    fun promptBlock(context: Context): String = runCatching {
        val app = context.applicationContext
        val packageInfo = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val registered = ToolRegistry.all.map { it.name }.toSet()
        val routes = OrionCapabilityAdapters.routes()
        val inventory = OrionPlatformInventory.read(app)
        val serviceConnected = PhoneAiService.instance != null
        val packs = runCatching {
            AgentPolicy.packs.associate { it.id to AgentPolicy.enabled(app, it.id) }
        }.getOrNull()
        val skills = runCatching { SkillRegistry.list(app) }.getOrNull()
        val enabledSkills = skills?.filter { it.enabled }.orEmpty()
        val extensions = runCatching { SelfExtendingExtensionStore(app).all() }.getOrNull()
        val verifiedExtensions = extensions?.count {
            it.enabled && it.verified &&
                it.sourceSha256 == SelfExtendingExtensionPolicy.sha256(it.source)
        }
        val termuxVisibility = runCatching {
            app.packageManager.getPackageInfo("com.termux", 0)
            "package visible"
        }.getOrElse { "not visible to this app (installation state unknown)" }

        buildString {
            appendLine("ORION PLATFORM SELF-KNOWLEDGE — CURRENT DEVICE AND RUNTIME")
            appendLine("This section is sampled for this turn. Prefer the strongest available supported layer for the task, adapt when a layer is unavailable, and verify the result. Orion-local per-tool DENY/ASK values and capability-pack switches are informational only and never disable registered tools. Honor Android permissions, actual service bindings and external authentication. Confirm only before deleting or wiping data and before each Trading Desk trade.")
            appendLine("ANDROID / HARDWARE LAYER: manufacturer=${Build.MANUFACTURER}; model=${Build.MODEL}; device=${Build.DEVICE}; Android=${Build.VERSION.RELEASE}; API=${Build.VERSION.SDK_INT}; supported_ABIs=${Build.SUPPORTED_ABIS?.take(6)?.joinToString().orEmpty().ifBlank { "unknown" }}; installed_app_version=${packageInfo?.versionName ?: "unknown"}; installed_app_last_update_ms=${packageInfo?.lastUpdateTime ?: 0L}; prime_identity=${OrionPrimeDirective.VERSION}; entity_doctrine=${OrionEntityPhilosophy.VERSION}. This describes the installed APK, not newer source changes on GitHub. Never claim source updates are active until a new build is installed. Hardware details are limited to Android build metadata; do not infer CPU/GPU/RAM specifications not supplied by a device-info tool.")
            appendLine("ANDROID SERVICE LAYER: accessibility_service=${if (serviceConnected) "CONNECTED" else "NOT CONNECTED"}; notification_listener and runtime permissions are reported separately below. A granted special-access setting does not prove the service process is bound.")
            appendLine("OWNER PERMISSIONS / PACKS / BACKGROUND WORK:")
            inventory.lines.forEach { appendLine("- $it") }
            appendLine("CAPABILITY PACK CATALOG: ${packs?.keys?.sorted()?.joinToString().orEmpty().ifBlank { "unavailable" }}. Pack switches are informational labels only; every registered built-in tool remains dispatch-eligible.")
            appendLine("SKILLS: ${if (skills == null) "UNKNOWN (registry read failed)" else "enabled=${enabledSkills.joinToString { it.id }.ifBlank { "none" }}; total=${skills.size}"}; skills may use only their declared tool grants.")
            appendLine("EXTENSIONS: ${if (extensions == null) "UNKNOWN (store read failed)" else "stored=${extensions.size}; verified_and_enabled=$verifiedExtensions"}; drafts, disabled extensions and unverified source are not executable capabilities.")
            appendLine("OPTIONAL TERMUX BRIDGE: $termuxVisibility. This is only package-visibility evidence; inspect the bridge's own readiness and permission result before use.")
            append(capabilityLayerMap(registered, routes))
            appendLine("ADAPTIVE ROUTING RULES:")
            appendLine("- PHONE CONTROL: prefer a supported direct Android intent/API when it can perform the requested operation; use the connected accessibility service for visible UI inspection and interaction; use browser-specific tools for the in-app browser. Do not substitute one layer blindly when its semantics differ.")
            appendLine("- COMPUTE: choose the least powerful runtime that satisfies the task: pure transformation/restricted Python for isolated computation; full Python/workspace only when files, packages, network or the phone bridge are actually required; Termux or Alpine/Linux only when their bridge/runtime is verified. Check architecture and installed dependencies before assuming a binary will run.")
            appendLine("- INFORMATION: use fresh device/provider/service reads when recency matters. Use memory, knowledge graph and prior experience to reduce repeated work, but treat them as historical evidence and revalidate current state.")
            appendLine("- INTELLIGENCE: use the selected model/provider that is eligible now; use vision only when image input is supported; use specialist workers for independent bounded work and keep Central Brain responsible for coordination, correction and final verification.")
            appendLine("- DEGRADED MODE: when a layer fails, inspect the actual error and readiness, select a compatible alternative layer, continue independent work, and record the blocker. Never claim a fallback worked until its result verifies it.")
            appendLine("- HARDWARE-AWARE ADAPTATION: before a hardware- or performance-sensitive task, use device_info to inspect the actual CPU, GPU, memory, storage, ABI and supported runtime details it exposes. Combine those facts with the battery, thermal, network and foreground/background snapshots. Select compatible binaries and the lightest model/runtime that meets the task; do not guess missing specifications.")
            appendLine("- RESOURCE ADAPTATION: consider current battery, thermal, network and foreground/background constraints when those snapshots are available. Prefer bounded local work on constrained/offline devices, defer non-urgent background work when Android may restrict it, and avoid unnecessary heavy models or repeated polling.")
            appendLine("- DISCOVERY: all registered tool names and argument signatures are exposed in the tool catalog. Use the exact registered contract; a missing detailed description can be retrieved from the source/tool registry rather than guessed. Never invent a tool name.")
            appendLine(OrionIdentity.changeBlock(app))
            appendLine(OrionWakeCheck.block(app))
            appendLine("TRUTH BOUNDARY: REGISTERED means present in the installed local registry; INSTALLED BUILD means the APK version and update timestamp reported above; SOURCE-UPDATED means a repository change exists but may not yet be installed; PERMISSIONED means the relevant OS grant is observed; CONNECTED means the required service/session is actually connected; VERIFIED means a real operation produced confirming evidence. These states are distinct.")
        }
    }.getOrElse { failure ->
        "ORION PLATFORM SELF-KNOWLEDGE: UNKNOWN; live inventory failed (${failure.javaClass.simpleName}). Use only tool contracts and runtime evidence actually supplied; do not infer platform readiness."
    }
}
