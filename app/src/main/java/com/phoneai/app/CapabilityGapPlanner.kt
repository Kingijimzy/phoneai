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
                "Inspect the full registered tool contracts already supplied in the system context. Use capability_search with an objective-level description if the required callable is outside the current function declarations; paginate until a compatible implemented route is found or has_more=false, then use capability_load with that exact tool_name before invoking it.",
                "Check live prerequisites through the relevant status/readiness tool and use the existing executor; registration alone is not proof of service health or permission.",
                "If a call fails, classify the actual error, inspect current state, and select a semantically equivalent route only when evidence supports it.",
                "Reuse registered tools, enabled skills and verified hash-matching extensions before creating anything new. For extension reuse, call extension_list, match the user objective to an enabled verified extension description, then call extension_run with that exact id and the required input; do not regenerate an equivalent tool.",
                "Only when the model has established a genuine capability gap, choose the smallest implementation route. For a reusable Python capability, first define the input/output contract and expected behavior; create a disabled draft with extension_create; supply representative runtime test cases covering normal input, empty/boundary input and invalid input where applicable; run extension_test in the selected real runtime; inspect every actual result and repair failures before retrying. Activation must be backed by a passing receipt for the exact source hash. Restricted python may auto-activate only when owner policy allows; python_full always requires separate owner approval to test and activate. After activation, verify it appears in extension_list, invoke it with extension_run, check its returned output against the requested outcome, and reuse it on later tasks.",
                "Python extensions can add reusable data/workflow logic but cannot add Android framework, AccessibilityService, privileged OS or app-native capabilities. Never describe PRoot as an OS security boundary.",
                "For a native Android/PhoneAI capability gap, switch to the developer workflow instead of forging Python: inspect the selected workspace with dev_understand; read the relevant source and build configuration with dev_read; prepare a minimal change plan with dev_plan; modify source only through the authorized dev_write/repository route; run the Gradle compile/assemble command through dev_shell with verification=build and the unit tests/check command with verification=test (or use the authorized CI workflow); inspect structured attempted/passed status, exit codes, test reports and produced artifacts. An APK build is not an installation; use verification=install only for a real package-install command. If the source lives in a remote repository, use the authorized GitHub/repository workflow and keep changes on a reviewable branch or PR. Verify the new capability through its real runtime entry point after the build. If the workspace, repository permission, Android SDK/build environment or owner approval is missing, report that exact blocker and preserve the partial work; never claim Python created a native Android capability."
            ),
            "No lexical similarity score is treated as a tool-selection decision. The model must assess semantic fit using the complete registry, live readiness context and actual execution evidence."
        )
    }
}
