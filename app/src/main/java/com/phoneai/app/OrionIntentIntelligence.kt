package com.phoneai.app

import kotlinx.serialization.Serializable

/**
 * Orion Intent Intelligence
 *
 * Turns a raw utterance into a structured objective the rest of the pipeline can reason about.
 *
 * What this actually does (not prompt text):
 *  - classifies objective shape from the request itself (question / single action / multi-step /
 *    research / code work / memory query / system control / delegation), reusing the project's
 *    deterministic routing policies where they already encode real routing knowledge;
 *  - extracts entities with OrionEntityIndex (real PERSON/PLACE/PROJECT/DATE extraction) and
 *    resolves them against Cerebro memory, so "message Mom" can find who Mom is;
 *  - derives constraints and success criteria a verifier can actually check;
 *  - scores confidence from evidence density (entities resolved, capability availability,
 *    action clarity) instead of a constant.
 */
internal class OrionIntentIntelligence(
    private val svc: PhoneAiService
) {

    @Serializable
enum class IntentType { QUESTION, ACTION, MULTI_STEP, MEMORY_QUERY, SYSTEM_CONTROL, CREATIVE, RESEARCH, DELEGATION, UNKNOWN }

    @Serializable
enum class Urgency { LOW, NORMAL, HIGH, CRITICAL }

    @Serializable
    data class Constraint(
        val type: String,
        val severity: String,
        val description: String
    )

    @Serializable
    data class SuccessCriterion(
        val description: String,
        val verificationMethod: String,
        val verifiable: Boolean = true
    )

    @Serializable
    data class ContextualReference(
        val type: String,
        val target: String,
        val confidence: Double
    )

    @Serializable
    data class EntityRef(
        val kind: String,
        val name: String,
        val resolvedFromMemory: Boolean
    )

    @Serializable
    data class Intent(
        val type: IntentType,
        val primaryObjective: String,
        val entities: Map<String, String>,
        val entityRefs: List<EntityRef>,
        val constraints: List<Constraint>,
        val dependencies: List<String>,
        val successCriteria: List<SuccessCriterion>,
        val urgency: Urgency,
        val confidence: Double,
        val contextualReferences: List<ContextualReference>,
        val requiredCapabilities: List<String>,
        val missingCapabilities: List<String>,
        val rationale: String
    )

    fun analyzeIntent(
        input: String,
        conversationHistory: List<String>,
        activeObjective: String,
        context: OrionContextIntegration.UnifiedContext?
    ): Intent {
        val text = input.trim()
        val lower = text.lowercase()
        val capabilities = context?.capabilities
        val memory = context?.memory

        val type = classify(text, lower)
        val urgency = urgencyOf(lower)

        // --- entities: real extraction, then memory resolution ---
        val extracted = runCatching { OrionEntityIndex.extract(text) }.getOrDefault(emptyList())
        val entityRefs = mutableListOf<EntityRef>()
        val entityMap = linkedMapOf<String, String>()

        for (e in extracted) {
            val known = if (e.kind == OrionEntityIndex.Kind.PERSON) {
                memory?.recalledMemories?.firstOrNull { it.contains(e.name, ignoreCase = true) }
            } else null
            entityRefs.add(EntityRef(e.kind.name, e.name, resolvedFromMemory = known != null))
            entityMap[e.name] = e.kind.name
            // Give the executor a concrete target instead of a bare pronoun phrase.
            if (known != null) entityMap["resolved:${e.name}"] = known.take(120)
        }
        // Relative-person references whose concrete name may live in memory ("my mom" -> "Mom").
        Regex("(?i)\\b(my|our)\\s+(mom|mum|mother|dad|father|sister|brother|wife|husband|boss|girlfriend|boyfriend)\\b")
            .findAll(text).forEach { m ->
                val who = m.groupValues[2]
                entityMap.putIfAbsent(who, "PERSON_RELATIVE")
            }

        // --- required capabilities: what the intent implies, checked against live inventory ---
        val required = requiredCapabilitiesFor(type, lower)
        val missing = if (capabilities != null) required.filterNot { capabilities.isAvailable(it) } else emptyList()

        // --- constraints ---
        val constraints = mutableListOf<Constraint>()
        if (urgency == Urgency.HIGH || urgency == Urgency.CRITICAL) {
            constraints.add(Constraint("urgency", "HIGH", "Owner asked for immediate handling"))
        }
        if (Regex("(?i)\\b(don't ask|no confirmation|without asking|just do it)\\b").containsMatchIn(text)) {
            constraints.add(Constraint("autonomy", "MEDIUM", "Owner asked not to be interrupted for confirmation"))
        }
        if (Regex("(?i)\\b(read only|don't change|do not change|just tell me)\\b").containsMatchIn(text)) {
            constraints.add(Constraint("read_only", "HIGH", "Owner asked for a read-only outcome"))
        }
        if (Regex("(?i)\\b(trading|trade|place an order|buy|sell|short|long position)\\b").containsMatchIn(text)) {
            constraints.add(Constraint("financial", "CRITICAL", "Trading Desk action requires explicit Boss approval"))
        }
        if (missing.isNotEmpty()) {
            constraints.add(Constraint("capability", "HIGH", "Missing capability: " + missing.joinToString(", ")))
        }

        // --- success criteria: only things a verifier can actually check ---
        val successCriteria = mutableListOf<SuccessCriterion>()
        when (type) {
            IntentType.ACTION, IntentType.MULTI_STEP -> successCriteria.add(SuccessCriterion(
                "The requested end state is visible or returned by a tool",
                "LOCAL VERIFY PASS after the last action, or explicit verify_send / verify_action PASS"
            ))
            IntentType.RESEARCH -> successCriteria.add(SuccessCriterion(
                "Sources were retrieved and a concrete answer was produced",
                "browser_snapshot content retrieved and quoted"
            ))
            IntentType.CREATIVE -> successCriteria.add(SuccessCriterion(
                "The artefact exists and its content matches the request",
                "file read-back, or python exit 0 plus expected output"
            ))
            IntentType.MEMORY_QUERY -> successCriteria.add(SuccessCriterion(
                "An answer grounded in stored memory is returned",
                "recalled memory hit list is non-empty"
            ))
            IntentType.QUESTION -> successCriteria.add(SuccessCriterion(
                "A direct answer is given without tool invocation",
                "conversational answer; no dispatch required",
                verifiable = false
            ))
            IntentType.SYSTEM_CONTROL -> successCriteria.add(SuccessCriterion(
                "Device state changed as requested",
                "settings read-back after the change"
            ))
            IntentType.DELEGATION -> successCriteria.add(SuccessCriterion(
                "Specialist returned a result and the parent integrated it",
                "receipt present and referenced in the parent output"
            ))
            IntentType.UNKNOWN -> successCriteria.add(SuccessCriterion(
                "Intent clarified with the owner",
                "clarifying question answered",
                verifiable = false
            ))
        }

        // --- dependencies: prior open objectives this objective builds on ---
        val dependencies = buildList {
            if (activeObjective.isNotBlank() && type != IntentType.QUESTION) add("active:$activeObjective")
            context?.missions?.activeObjectives
                ?.filter { it.status == "PENDING" || it.status == "RUNNING" }
                ?.forEach { add("objective:${it.id}") }
        }

        // --- contextual references ---
        val contextualRefs = mutableListOf<ContextualReference>()
        if (Regex("(?i)\\b(it|that|this|those|them|the same|again|instead)\\b").containsMatchIn(text)) {
            contextualRefs.add(ContextualReference("anaphora", activeObjective.ifBlank { "previous turn" }, 0.6))
        }
        if (Regex("(?i)\\b(continue|carry on|finish|resume|pick up)\\b").containsMatchIn(text)) {
            contextualRefs.add(ContextualReference("continue", activeObjective, 0.85))
        }
        if (Regex("(?i)\\b(no,? |actually|instead|rather)\\b").containsMatchIn(text)) {
            contextualRefs.add(ContextualReference("correction", conversationHistory.lastOrNull().orEmpty(), 0.7))
        }

        // --- confidence from evidence, not a constant ---
        var confidence = 0.35
        if (type != IntentType.UNKNOWN) confidence += 0.15
        if (extracted.isNotEmpty()) confidence += 0.15
        if (entityRefs.any { it.resolvedFromMemory }) confidence += 0.10
        if (required.isNotEmpty() && missing.isEmpty()) confidence += 0.10
        if (missing.isNotEmpty()) confidence -= 0.10
        if (contextualRefs.isNotEmpty()) confidence += 0.05
        if (memory != null && memory.recalledMemories.isNotEmpty()) confidence += 0.05
        confidence = confidence.coerceIn(0.05, 0.95)

        val rationale = buildString {
            append("type=").append(type)
            append("; confidence=").append(String.format("%.2f", confidence))
            append("; entities=").append(entityRefs.size)
            if (entityRefs.any { it.resolvedFromMemory }) append(" (memory-resolved)")
            append("; required=").append(required.joinToString(",").ifBlank { "none" })
            if (missing.isNotEmpty()) append("; MISSING=").append(missing.joinToString(","))
            append("; constraints=").append(constraints.size)
            if (contextualRefs.isNotEmpty()) append("; context=").append(contextualRefs.joinToString(",") { it.type })
        }

        return Intent(
            type = type,
            primaryObjective = text,
            entities = entityMap,
            entityRefs = entityRefs,
            constraints = constraints,
            dependencies = dependencies,
            successCriteria = successCriteria,
            urgency = urgency,
            confidence = confidence,
            contextualReferences = contextualRefs,
            requiredCapabilities = required,
            missingCapabilities = missing,
            rationale = rationale
        )
    }

    private fun classify(text: String, lower: String): IntentType {
        // Deterministic project policies first — they encode real routing knowledge.
        if (runCatching { ConversationAutoRoutePolicy.requiresToolAgent(text) }.getOrDefault(false)) {
            return if (looksMultiStep(text)) IntentType.MULTI_STEP else IntentType.ACTION
        }
        if (runCatching { OrionCommandIntentPolicy.requiresDirectActionRouting(text) }.getOrDefault(false)) {
            return if (looksMultiStep(text)) IntentType.MULTI_STEP else IntentType.ACTION
        }
        if (Regex("(?i)^\\s*(ask|delegate to|have the)\\b.*\\b(agent|specialist)\\b").containsMatchIn(text)) {
            return IntentType.DELEGATION
        }
        if (Regex("(?i)\\b(search|google|look up|research|find out|browse)\\b").containsMatchIn(text)) {
            return IntentType.RESEARCH
        }
        if (Regex("(?i)\\b(remember|recall|what did i|we discussed|earlier you|you told me)\\b").containsMatchIn(text)) {
            return IntentType.MEMORY_QUERY
        }
        if (Regex("(?i)\\b(python|script|write code|run a program|patch|refactor|build the app)\\b").containsMatchIn(text)) {
            return IntentType.CREATIVE
        }
        if (Regex("(?i)\\b(restart|shutdown|reboot|wifi|wi-fi|bluetooth|airplane mode)\\b").containsMatchIn(text)) {
            return IntentType.SYSTEM_CONTROL
        }
        if (looksMultiStep(text)) return IntentType.MULTI_STEP
        if (Regex("(?i)\\b(open|launch|start|click|tap|swipe|type|send|call|message|install|uninstall|toggle|play|pause|set)\\b")
                .containsMatchIn(text)
        ) return IntentType.ACTION
        if (text.endsWith("?") || Regex("(?i)^\\s*(what|who|why|how|when|where|which|can you|could you|do you|is it|are there)\\b")
                .containsMatchIn(text)
        ) return IntentType.QUESTION
        return IntentType.UNKNOWN
    }

    private fun looksMultiStep(text: String): Boolean =
        Regex("(?i)\\b(and then|then|after that|followed by|first|second|finally)\\b").containsMatchIn(text) ||
            distinctOperations(text) >= 2

    private fun distinctOperations(text: String): Int =
        Regex("(?i)\\b(open|launch|send|type|click|search|download|install|save|create|call|message|copy|move|delete)\\b")
            .findAll(text).map { it.value.lowercase() }.distinct().count()

    private fun urgencyOf(lower: String): Urgency = when {
        Regex("\\b(asap|immediately|right now|emergency)\\b").containsMatchIn(lower) -> Urgency.CRITICAL
        Regex("\\b(urgent|quickly|hurry|now)\\b").containsMatchIn(lower) -> Urgency.HIGH
        Regex("\\b(whenever|no rush|later|sometime)\\b").containsMatchIn(lower) -> Urgency.LOW
        else -> Urgency.NORMAL
    }

    /** Capabilities the intent implies. Used for pre-execution gap detection, never for guessing. */
    private fun requiredCapabilitiesFor(type: IntentType, lower: String): List<String> = when (type) {
        IntentType.ACTION -> buildList {
            if (Regex("\\b(open|launch|start)\\b").containsMatchIn(lower)) add("open_app")
            if (Regex("\\b(click|tap|swipe|scroll)\\b").containsMatchIn(lower)) add("click")
            if (Regex("\\b(type|write|enter)\\b").containsMatchIn(lower)) add("type_text")
            if (Regex("\\b(send|message|text)\\b").containsMatchIn(lower)) add("send_message")
            if (Regex("\\b(call|dial)\\b").containsMatchIn(lower)) add("call")
            if (isEmpty()) add("read_screen")
        }.distinct()
        IntentType.MULTI_STEP -> listOf("read_screen", "click")
        IntentType.RESEARCH -> listOf("browser_navigate", "browser_snapshot")
        IntentType.CREATIVE -> listOf("python_exec")
        IntentType.MEMORY_QUERY -> listOf("memory")
        IntentType.SYSTEM_CONTROL -> listOf("shell")
        IntentType.DELEGATION -> listOf("ask_agent")
        IntentType.QUESTION, IntentType.UNKNOWN -> emptyList()
    }
}