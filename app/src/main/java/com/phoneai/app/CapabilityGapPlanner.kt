package com.phoneai.app

/**
 * Evidence-first capability-gap context for Orion's self-extension loop.
 *
 * This component deliberately does not choose a tool by token overlap. The model owns
 * semantic interpretation of the user's objective; live tool contracts, runtime status,
 * and execution receipts determine whether a route actually works.
 */
internal enum class CapabilityGapRoute { MODEL_DECIDES, NEEDS_CLARIFICATION }

internal data class CapabilityGapAssessment(
    val route: CapabilityGapRoute,
    val requestSummary: String,
    val candidates: List<String>,
    val nextActions: List<String>,
    val reason: String
) {
    fun promptBlock(): String = buildString {
        appendLine("CAPABILITY GAP ASSESSMENT")
        appendLine("route=${route.name}; request=${requestSummary.take(180)}")
        appendLine("candidates=${candidates.joinToString().ifBlank { "inspect the full registered inventory and live status" }}")
        appendLine("reason=${reason.take(320)}")
        nextActions.forEachIndexed { index, action -> appendLine("${index + 1}. $action") }
    }
}

internal object CapabilityGapPlanner {
    fun assess(
        request: String,
        registeredTools: List<ToolDefinition>,
        enabledSkills: List<PhoneAiSkill>,
        extensions: List<GeneratedExtension>
    ): CapabilityGapAssessment {
        val normalized = request.trim().replace(Regex("\\s+"), " ").take(180)
        if (normalized.isBlank()) return CapabilityGapAssessment(
            CapabilityGapRoute.NEEDS_CLARIFICATION, "", emptyList(),
            listOf("Ask for the intended outcome and what a successful result should look like."),
            "A capability gap cannot be assessed without a concrete goal."
        )

        val usableExtensions = extensions.filter {
            it.enabled && it.verified && it.sourceSha256 == SelfExtendingExtensionPolicy.sha256(it.source)
        }
        val inventory = buildList {
            add("registered tools=${registeredTools.size}")
            add("enabled skills=${enabledSkills.size}")
            add("verified enabled extensions=${usableExtensions.size}")
            if (enabledSkills.isNotEmpty()) add("skills: ${enabledSkills.take(12).joinToString { it.id }}")
            if (usableExtensions.isNotEmpty()) add("extensions: ${usableExtensions.take(12).joinToString { it.id }}")
        }

        return CapabilityGapAssessment(
            CapabilityGapRoute.MODEL_DECIDES,
            normalized,
            inventory,
            listOf(
                "Interpret the requested outcome, context, constraints and observable success condition; do not infer a tool from matching words.",
                "Inspect the full registered tool contracts already supplied in the system context. Use capability_search with an objective-level description if the required callable is outside the current function declarations.",
                "Check live prerequisites through the relevant status/readiness tool and use the existing executor; registration alone is not proof of service health or permission.",
                "If a call fails, classify the actual error, inspect current state, and select a semantically equivalent route only when evidence supports it.",
                "Reuse registered tools, enabled skills and verified hash-matching extensions before creating anything new.",
                "Only when the model has established a genuine capability gap, choose the smallest implementation route. Python extensions can add reusable data/workflow logic but cannot add Android framework, AccessibilityService, privileged OS or app-native capabilities.",
                "For a native Android/app capability gap, inspect source and use the authorized developer/repository workflow when available; otherwise report the precise missing workspace or permission without pretending an extension solved it."
            ),
            "No lexical similarity score is treated as a tool-selection decision. The model must assess semantic fit using the complete registry, live readiness context and actual execution evidence."
        )
    }
}
