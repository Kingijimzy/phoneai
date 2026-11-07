package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Orion Intent Intelligence
 *
 * Understands user objectives from natural language and context.
 * Extracts: intent type, entities, constraints, dependencies, success criteria, and urgency.
 */
class OrionIntentIntelligence(
    private val svc: PhoneAiService,
    private val embeddingProvider: OrionEmbeddingProvider,
    private val recall: OrionRecall,
    private val entityIndex: OrionEntityIndex,
    private val knowledgeGraph: OrionKnowledgeGraph
) {

    data class Intent(
        val type: IntentType,
        val primaryObjective: String,
        val entities: Map<String, String>, // entity -> type
        val constraints: List<Constraint>,
        val dependencies: List<String>, // objective IDs this depends on
        val successCriteria: List<SuccessCriterion>,
        val urgency: Urgency,
        val confidence: Double,
        val contextualReferences: List<ContextualReference>,
        val suggestedCapabilities: List<String>
    )

    enum class IntentType {
        QUESTION,           // Information seeking
        ACTION,             // Single tool execution
        MULTI_STEP_TASK,    // Multiple dependent steps
        RESEARCH,           // Information gathering + synthesis
        CODING,             // Write/run/debug code
        AUTOMATION,         // Browser/app automation
        MEMORY_OP,          // Remember/recall/forget
        SCHEDULING,         // Reminders/calendar
        TRADING,            // Trading operations
        CREATIVE,           // Content generation
        SYSTEM_CONTROL,     // Device settings
        CORRECTION,         // Fix previous action
        CONTINUATION,       // Resume incomplete work
        CLARIFICATION,      // Ask for clarification
        UNKNOWN
    }

    enum class Urgency { LOW, NORMAL, HIGH, CRITICAL }

    data class Constraint(
        val type: ConstraintType,
        val description: String,
        val severity: Severity // HARD = must satisfy, SOFT = prefer
    ) {
        enum class ConstraintType {
            TIME,           // Deadline, time window
            RESOURCE,       // Budget, API quota, battery
            PERMISSION,     // Android permission, user approval
            CAPABILITY,     // Missing tool/skill
            SECURITY,       // Sensitive data, irreversible action
            DEPENDENCY,     // Requires other task completion
            PLATFORM        // OS version, hardware limitation
        }
        enum class Severity { HARD, SOFT }
    }

    data class SuccessCriterion(
        val description: String,
        val verificationMethod: VerificationMethod,
        val expectedEvidence: String,
        val weight: Double = 1.0
    ) {
        enum class VerificationMethod {
            SCREEN_CHANGE,      // Visible UI change
            TOOL_OUTPUT,        // Specific output pattern
            FILE_EXISTS,        // File created/modified
            MESSAGE_SENT,       // verify_send PASS
            VERIFY_ACTION,      // verify_action PASS
            SHELL_EXIT_CODE,    // Exit code 0
            MEMORY_RECORDED,    // Fact stored in memory
            OBJECTIVE_COMPLETE, // Objective graph node COMPLETE
            EXTERNAL_API        // External service confirmation
        }
    }

    data class ContextualReference(
        val reference: String,      // "that", "it", "the file", "my previous task"
        val resolvedEntity: String?, // Resolved entity ID or null
        val referenceType: ReferenceType
    ) {
        enum class ReferenceType { PRONOUN, DEFINITE_NOUN, TEMPORAL, SPATIAL, ANAPHORIC }
    }

    /**
     * Analyze user input and context to extract structured intent.
     */
    fun analyzeIntent(
        input: String,
        conversationHistory: List<Pair<String, String>>,
        activeObjective: String?,
        deviceContext: OrionChronosAwareness.DeviceContext?,
        availableCapabilities: Set<String>,
        missionHistory: List<OrionObjectiveGraph.MissionRecord>
    ): Intent {
        // 1. Extract entities from input and context
        val entities = extractEntities(input, conversationHistory, activeObjective)

        // 2. Resolve contextual references (pronouns, anaphora)
        val contextualRefs = resolveContextualReferences(input, conversationHistory, activeObjective)

        // 3. Classify intent type
        val intentType = classifyIntentType(input, entities, contextualRefs, activeObjective)

        // 4. Extract constraints from context and input
        val constraints = extractConstraints(input, deviceContext, availableCapabilities, intentType)

        // 5. Define success criteria based on intent type
        val successCriteria = defineSuccessCriteria(intentType, entities, input)

        // 6. Determine urgency
        val urgency = determineUrgency(input, deviceContext, intentType)

        // 7. Identify dependencies on existing objectives
        val dependencies = identifyDependencies(input, activeObjective, missionHistory)

        // 8. Suggest relevant capabilities
        val suggestedCapabilities = suggestCapabilities(intentType, entities, constraints, availableCapabilities)

        // 9. Calculate confidence
        val confidence = calculateConfidence(intentType, entities, constraints, contextualRefs)

        return Intent(
            type = intentType,
            primaryObjective = extractPrimaryObjective(input, intentType),
            entities = entities,
            constraints = constraints,
            dependencies = dependencies,
            successCriteria = successCriteria,
            urgency = urgency,
            confidence = confidence,
            contextualReferences = contextualRefs,
            suggestedCapabilities = suggestedCapabilities
        )
    }

    private fun extractEntities(
        input: String,
        history: List<Pair<String, String>>,
        activeObjective: String?
    ): Map<String, String> {
        val entities = mutableMapOf<String, String>()

        // Extract from knowledge graph
        val kgEntities = knowledgeGraph.findEntitiesInText(input)
        for ((entity, type) in kgEntities) entities[entity] = type

        // Extract from entity index (recent mentions)
        val recentEntities = entityIndex.getRecentEntities(history, limit = 10)
        for ((entity, type) in recentEntities) entities[entity] = type

        // Extract from active objective context
        activeObjective?.let {
            val objEntities = knowledgeGraph.findEntitiesInText(it)
            for ((entity, type) in objEntities) entities[entity] = type
        }

        // Regex-based extraction for common patterns
        extractRegexEntities(input).forEach { (k, v) -> entities[k] = v }

        return entities
    }

    private fun extractRegexEntities(input: String): Map<String, String> {
        val entities = mutableMapOf<String, String>()
        val patterns = mapOf(
            Regex("(?i)\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b") to "PROPER_NOUN",
            Regex("(?i)\\b(\\d{1,2}:\\d{2}\\s*(?:am|pm)?)\\b") to "TIME",
            Regex("(?i)\\b(today|tomorrow|yesterday|next\\s+week|this\\s+week)\\b") to "DATE_RELATIVE",
            Regex("(?i)\\b(https?://[^\\s]+)\\b") to "URL",
            Regex("(?i)\\b([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})\\b") to "EMAIL",
            Regex("(?i)\\b(\\$?\\d+(?:,\\d{3})*(?:\\.\\d{2})?)\\b") to "AMOUNT",
            Regex("(?i)\\b(BTC|ETH|SOL|USDT|USDC|BNB|XRP|ADA|DOGE)\\b") to "CRYPTO_SYMBOL"
        )

        for ((pattern, type) in patterns) {
            pattern.findAll(input).forEach { match ->
                entities[match.value] = type
            }
        }
        return entities
    }

    private fun resolveContextualReferences(
        input: String,
        history: List<Pair<String, String>>,
        activeObjective: String?
    ): List<ContextualReference> {
        val refs = mutableListOf<ContextualReference>()

        // Pronoun resolution
        val pronouns = mapOf(
            "it" to ContextualReference.ReferenceType.PRONOUN,
            "that" to ContextualReference.ReferenceType.PRONOUN,
            "this" to ContextualReference.ReferenceType.PRONOUN,
            "them" to ContextualReference.ReferenceType.PRONOUN,
            "those" to ContextualReference.ReferenceType.PRONOUN
        )

        for ((pronoun, refType) in pronouns) {
            if (Regex("\\b$pronoun\\b", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
                val resolved = resolvePronoun(pronoun, history, activeObjective)
                refs.add(ContextualReference(pronoun, resolved, refType))
            }
        }

        // Definite noun phrases ("the file", "the message", "the task")
        val definiteNouns = Regex("(?i)\\bthe\\s+(\\w+(?:\\s+\\w+)*)\\b").findAll(input)
        for (match in definiteNouns) {
            val noun = match.groupValues[1]
            val resolved = resolveDefiniteNoun(noun, history, activeObjective)
            refs.add(ContextualReference("the $noun", resolved, ContextualReference.ReferenceType.DEFINITE_NOUN))
        }

        // Temporal references ("last time", "earlier", "yesterday")
        val temporalRefs = Regex("(?i)\\b(last time|earlier|yesterday|before|previously)\\b").findAll(input)
        for (match in temporalRefs) {
            refs.add(ContextualReference(match.value, activeObjective, ContextualReference.ReferenceType.TEMPORAL))
        }

        return refs
    }

    private fun resolvePronoun(
        pronoun: String,
        history: List<Pair<String, String>>,
        activeObjective: String?
    ): String? {
        // Simple resolution: most recent entity from assistant responses
        for ((role, text) in history.reversed()) {
            if (role == "assistant") {
                val entities = knowledgeGraph.findEntitiesInText(text)
                if (entities.isNotEmpty()) return entities.keys.first()
            }
        }
        return activeObjective
    }

    private fun resolveDefiniteNoun(
        noun: String,
        history: List<Pair<String, String>>,
        activeObjective: String?
    ): String? {
        // Search recent context for matching entity
        for ((role, text) in history.reversed()) {
            if (text.contains(noun, ignoreCase = true)) {
                val entities = knowledgeGraph.findEntitiesInText(text)
                return entities.keys.firstOrNull { it.contains(noun, ignoreCase = true) }
            }
        }
        return null
    }

    private fun classifyIntentType(
        input: String,
        entities: Map<String, String>,
        contextualRefs: List<ContextualReference>,
        activeObjective: String?
    ): IntentType {
        val lower = input.lowercase()

        // Explicit continuation/correction
        if (contextualRefs.any { it.referenceType == ContextualReference.ReferenceType.TEMPORAL }) {
            return if (lower.contains("fix") || lower.contains("correct") || lower.contains("wrong"))
                IntentType.CORRECTION else IntentType.CONTINUATION
        }

        // Active objective context
        if (activeObjective != null && activeObjective.isNotBlank()) {
            if (lower.contains("continue") || lower.contains("resume") || lower.contains("finish"))
                return IntentType.CONTINUATION
            if (lower.contains("fix") || lower.contains("correct") || lower.contains("redo"))
                return IntentType.CORRECTION
        }

        // Trading keywords
        if (lower.contains("trade") || lower.contains("buy") || lower.contains("sell") ||
            lower.contains("position") || lower.contains("portfolio") || entities.containsKey("CRYPTO_SYMBOL")) {
            return IntentType.TRADING
        }

        // Coding keywords
        if (lower.contains("code") || lower.contains("script") || lower.contains("function") ||
            lower.contains("class") || lower.contains("debug") || lower.contains("python") ||
            lower.contains("javascript") || lower.contains("kotlin")) {
            return IntentType.CODING
        }

        // Research keywords
        if (lower.contains("research") || lower.contains("investigate") || lower.contains("find out") ||
            lower.contains("look up") || lower.contains("search for") || lower.contains("browse")) {
            return IntentType.RESEARCH
        }

        // Automation keywords
        if (lower.contains("automate") || lower.contains("click") || lower.contains("tap") ||
            lower.contains("open") && (lower.contains("app") || lower.contains("website")) ||
            lower.contains("browser") || lower.contains("navigate")) {
            return IntentType.AUTOMATION
        }

        // Memory operations
        if (lower.contains("remember") || lower.contains("recall") || lower.contains("forget") ||
            lower.contains("save this") || lower.contains("note that")) {
            return IntentType.MEMORY_OP
        }

        // Scheduling
        if (lower.contains("remind") || lower.contains("schedule") || lower.contains("alarm") ||
            lower.contains("timer") || lower.contains("calendar")) {
            return IntentType.SCHEDULING
        }

        // System control
        if (lower.contains("wifi") || lower.contains("bluetooth") || lower.contains("brightness") ||
            lower.contains("volume") || lower.contains("restart") || lower.contains("shutdown")) {
            return IntentType.SYSTEM_CONTROL
        }

        // Creative
        if (lower.contains("write") || lower.contains("create") || lower.contains("generate") ||
            lower.contains("compose") || lower.contains("draft") || lower.contains("summarize")) {
            return IntentType.CREATIVE
        }

        // Question vs Action
        val questionWords = Regex("(?i)^(what|who|where|when|why|how|which|can you|could you|tell me|explain)")
        if (questionWords.containsMatchIn(lower) && !lower.contains("do") && !lower.contains("make") && !lower.contains("send")) {
            return IntentType.QUESTION
        }

        // Multi-step detection
        val stepIndicators = Regex("(?i)\\b(then|after|before|next|step|first|second|finally)\\b")
        if (stepIndicators.containsMatchIn(lower) || lower.contains(",") && lower.length > 50) {
            return IntentType.MULTI_STEP_TASK
        }

        return IntentType.ACTION
    }

    private fun extractConstraints(
        input: String,
        deviceContext: OrionChronosAwareness.DeviceContext?,
        availableCapabilities: Set<String>,
        intentType: IntentType
    ): List<Constraint> {
        val constraints = mutableListOf<Constraint>()
        val lower = input.lowercase()

        // Time constraints
        val timePatterns = Regex("(?i)\\b(by|before|within|in)\\s+(\\d+)\\s*(minute|hour|day|week)s?\\b")
        timePatterns.findAll(input).forEach { match ->
            constraints.add(Constraint(
                Constraint.ConstraintType.TIME,
                "Deadline: ${match.value}",
                Constraint.Severity.HARD
            ))
        }

        // Resource constraints
        if (lower.contains("budget") || lower.contains("quota") || lower.contains("limit")) {
            constraints.add(Constraint(Constraint.ConstraintType.RESOURCE, "Budget/quota limit mentioned", Constraint.Severity.SOFT))
        }

        // Battery constraint
        deviceContext?.batteryLevel?.let { battery ->
            if (battery < 20) {
                constraints.add(Constraint(Constraint.ConstraintType.RESOURCE, "Low battery ($battery%)", Constraint.Severity.HARD))
            } else if (battery < 40) {
                constraints.add(Constraint(Constraint.ConstraintType.RESOURCE, "Moderate battery ($battery%)", Constraint.Severity.SOFT))
            }
        }

        // Permission constraints
        if (intentType == IntentType.SYSTEM_CONTROL || intentType == IntentType.AUTOMATION) {
            constraints.add(Constraint(Constraint.ConstraintType.PERMISSION, "May require Android permissions", Constraint.Severity.HARD))
        }
        if (intentType == IntentType.TRADING) {
            constraints.add(Constraint(Constraint.ConstraintType.PERMISSION, "Trading requires explicit user approval", Constraint.Severity.HARD))
        }
        if (intentType == IntentType.CODING && lower.contains("install")) {
            constraints.add(Constraint(Constraint.ConstraintType.PERMISSION, "Package installation may need approval", Constraint.Severity.SOFT))
        }

        // Capability constraints
        val requiredCapabilities = when (intentType) {
            IntentType.CODING -> setOf("python_exec", "shell")
            IntentType.RESEARCH -> setOf("web_search", "browser_navigate", "fetch_url")
            IntentType.AUTOMATION -> setOf("open_app", "click", "browser_navigate")
            IntentType.TRADING -> setOf("trading_place_order", "trading_portfolio")
            IntentType.SYSTEM_CONTROL -> setOf("computer_settings_wifi_toggle", "computer_settings_brightness")
            else -> emptySet<String>()
        }
        val missing = requiredCapabilities - availableCapabilities
        for (cap in missing) {
            constraints.add(Constraint(Constraint.ConstraintType.CAPABILITY, "Missing capability: $cap", Constraint.Severity.HARD))
        }

        // Security constraints
        if (lower.contains("password") || lower.contains("credential") || lower.contains("private key") ||
            lower.contains("seed phrase") || lower.contains("delete") || lower.contains("wipe")) {
            constraints.add(Constraint(Constraint.ConstraintType.SECURITY, "Sensitive operation detected", Constraint.Severity.HARD))
        }

        // Dependency constraints
        if (lower.contains("after") || lower.contains("once") || lower.contains("when")) {
            constraints.add(Constraint(Constraint.ConstraintType.DEPENDENCY, "Implicit dependency on prior action", Constraint.Severity.SOFT))
        }

        return constraints
    }

    private fun defineSuccessCriteria(
        intentType: IntentType,
        entities: Map<String, String>,
        input: String
    ): List<SuccessCriterion> {
        val criteria = mutableListOf<SuccessCriterion>()

        when (intentType) {
            IntentType.ACTION, IntentType.AUTOMATION -> {
                criteria.add(SuccessCriterion(
                    "Visible screen change confirms action",
                    SuccessCriterion.VerificationMethod.SCREEN_CHANGE,
                    "UI state changed after action",
                    0.8
                ))
            }
            IntentType.CODING -> {
                criteria.add(SuccessCriterion(
                    "Code executes without errors",
                    SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    "Exit code 0 or expected output",
                    0.9
                ))
                criteria.add(SuccessCriterion(
                    "File created or modified",
                    SuccessCriterion.VerificationMethod.FILE_EXISTS,
                    "Target file exists with expected content",
                    0.8
                ))
            }
            IntentType.RESEARCH -> {
                criteria.add(SuccessCriterion(
                    "Relevant information retrieved and synthesized",
                    SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    "Search results contain answer to query",
                    0.9
                ))
            }
            IntentType.TRADING -> {
                criteria.add(SuccessCriterion(
                    "Order confirmed by exchange",
                    SuccessCriterion.VerificationMethod.EXTERNAL_API,
                    "Order ID returned from exchange",
                    1.0
                ))
            }
            IntentType.MEMORY_OP -> {
                criteria.add(SuccessCriterion(
                    "Fact stored in long-term memory",
                    SuccessCriterion.VerificationMethod.MEMORY_RECORDED,
                    "Memory record created",
                    0.9
                ))
            }
            IntentType.SCHEDULING -> {
                criteria.add(SuccessCriterion(
                    "Reminder/alarm scheduled",
                    SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    "Confirmation of scheduled time",
                    0.9
                ))
            }
            IntentType.SYSTEM_CONTROL -> {
                criteria.add(SuccessCriterion(
                    "Setting changed and verified",
                    SuccessCriterion.VerificationMethod.VERIFY_ACTION,
                    "Setting reflects new value",
                    0.9
                ))
            }
            IntentType.CREATIVE -> {
                criteria.add(SuccessCriterion(
                    "Content generated and delivered",
                    SuccessCriterion.VerificationMethod.TOOL_OUTPUT,
                    "Generated content present in response",
                    0.8
                ))
            }
            IntentType.MULTI_STEP_TASK -> {
                criteria.add(SuccessCriterion(
                    "All steps completed successfully",
                    SuccessCriterion.VerificationMethod.OBJECTIVE_COMPLETE,
                    "All objective graph nodes COMPLETE",
                    1.0
                ))
            }
            IntentType.CORRECTION -> {
                criteria.add(SuccessCriterion(
                    "Previous error corrected",
                    SuccessCriterion.VerificationMethod.VERIFY_ACTION,
                    "Correct outcome verified",
                    0.9
                ))
            }
            IntentType.CONTINUATION -> {
                criteria.add(SuccessCriterion(
                    "Objective progressed or completed",
                    SuccessCriterion.VerificationMethod.OBJECTIVE_COMPLETE,
                    "Active objective advanced",
                    0.9
                ))
            }
        }

        // Always add basic verification
        if (intentType != IntentType.QUESTION && intentType != IntentType.CLARIFICATION) {
            criteria.add(SuccessCriterion(
                "Action verified by appropriate method",
                SuccessCriterion.VerificationMethod.VERIFY_ACTION,
                "Verification tool confirms outcome",
                0.7
            ))
        }

        return criteria
    }

    private fun determineUrgency(
        input: String,
        deviceContext: OrionChronosAwareness.DeviceContext?,
        intentType: IntentType
    ): Urgency {
        val lower = input.lowercase()

        // Critical urgency indicators
        if (lower.contains("emergency") || lower.contains("urgent") || lower.contains("asap") ||
            lower.contains("immediately") || lower.contains("right now") || lower.contains("critical")) {
            return Urgency.CRITICAL
        }

        // High urgency
        if (lower.contains("soon") || lower.contains("quickly") || lower.contains("hurry") ||
            intentType == IntentType.TRADING || intentType == IntentType.SYSTEM_CONTROL) {
            return Urgency.HIGH
        }

        // Low urgency
        if (lower.contains("when you can") || lower.contains("no rush") || lower.contains("eventually") ||
            lower.contains("later") || lower.contains("sometime")) {
            return Urgency.LOW
        }

        // Device context urgency
        deviceContext?.batteryLevel?.let { battery ->
            if (battery < 15) return Urgency.HIGH
        }

        return Urgency.NORMAL
    }

    private fun identifyDependencies(
        input: String,
        activeObjective: String?,
        missionHistory: List<OrionObjectiveGraph.MissionRecord>
    ): List<String> {
        val deps = mutableListOf<String>()

        // Explicit dependency mentions
        val depPattern = Regex("(?i)\\b(after|once|when)\\s+(\\w+(?:\\s+\\w+)*)\\s+(?:is|are|was|were)\\s+(?:done|complete|finished)")
        depPattern.findAll(input).forEach { match ->
            deps.add(match.groupValues[2])
        }

        // Active objective as dependency
        activeObjective?.let { deps.add(it) }

        // Recent incomplete objectives
        for (mission in missionHistory.take(5)) {
            if (mission.status == OrionObjectiveGraph.MissionStatus.IN_PROGRESS) {
                deps.add(mission.objectiveId)
            }
        }

        return deps.distinct()
    }

    private fun suggestCapabilities(
        intentType: IntentType,
        entities: Map<String, String>,
        constraints: List<Constraint>,
        availableCapabilities: Set<String>
    ): List<String> {
        val suggestions = mutableSetOf<String>()

        val capabilityMap = mapOf(
            IntentType.CODING to listOf("python_exec", "shell", "file_write", "file_read"),
            IntentType.RESEARCH to listOf("web_search", "browser_navigate", "fetch_url", "browser_snapshot"),
            IntentType.AUTOMATION to listOf("open_app", "click", "swipe", "type_text", "browser_navigate"),
            IntentType.TRADING to listOf("trading_place_order", "trading_portfolio", "trading_balance", "trading_ticker"),
            IntentType.SYSTEM_CONTROL to listOf("computer_settings_wifi_toggle", "computer_settings_brightness", "computer_settings_volume"),
            IntentType.MEMORY_OP to listOf("memory", "entity_query", "knowledge_query"),
            IntentType.SCHEDULING to listOf("reminder_add", "calendar_query"),
            IntentType.CREATIVE to listOf("python_exec", "file_write"),
            IntentType.MULTI_STEP_TASK to listOf("task_add", "macro_save", "objective_graph")
        )

        capabilityMap[intentType]?.forEach { cap ->
            if (cap in availableCapabilities) suggestions.add(cap)
        }

        // Entity-specific suggestions
        if (entities.containsKey("CRYPTO_SYMBOL")) {
            suggestions.add("trading_ticker")
            suggestions.add("trading_market_info")
        }
        if (entities.containsKey("URL")) {
            suggestions.add("browser_navigate")
            suggestions.add("fetch_url")
        }

        return suggestions.toList()
    }

    private fun calculateConfidence(
        intentType: IntentType,
        entities: Map<String, String>,
        constraints: List<Constraint>,
        contextualRefs: List<ContextualReference>
    ): Double {
        var confidence = 0.7 // Base confidence

        // Higher confidence for explicit intent types
        when (intentType) {
            IntentType.TRADING, IntentType.CODING, IntentType.SCHEDULING -> confidence += 0.2
            IntentType.QUESTION, IntentType.ACTION -> confidence += 0.1
            IntentType.UNKNOWN -> confidence -= 0.3
        }

        // Entities increase confidence
        confidence += minOf(entities.size * 0.05, 0.2)

        // Contextual references resolved increase confidence
        val resolvedRefs = contextualRefs.count { it.resolvedEntity != null }
        confidence += resolvedRefs * 0.05

        // Hard constraints reduce confidence (more complex)
        val hardConstraints = constraints.count { it.severity == Constraint.Severity.HARD }
        confidence -= hardConstraints * 0.03

        return confidence.coerceIn(0.1, 1.0)
    }

    private fun extractPrimaryObjective(input: String, intentType: IntentType): String {
        // Simplified: return input truncated for now
        // In practice, would extract the core actionable objective
        return input.take(200)
    }
}

/**
 * Lightweight intent analysis for quick routing decisions.
 */
object OrionQuickIntent {
    private val actionVerbs = Regex("(?i)\\b(open|send|create|make|build|run|execute|install|delete|remove|set|change|turn|enable|disable|start|stop|restart|search|find|get|fetch|download|upload|save|remember|remind|schedule|calculate|compute|trade|buy|sell)\\b")
    private val questionWords = Regex("(?i)^(what|who|where|when|why|how|which|can|could|would|should|is|are|do|does|tell me|explain)")
    private val continuationWords = Regex("(?i)\\b(continue|resume|finish|complete|fix|correct|redo|retry|also|and then|next)\\b")

    fun isActionRequest(input: String): Boolean = actionVerbs.containsMatchIn(input)

    fun isQuestion(input: String): Boolean = questionWords.containsMatchIn(input)

    fun isContinuation(input: String, hasActiveObjective: Boolean): Boolean =
        hasActiveObjective && continuationWords.containsMatchIn(input)

    fun quickRoute(input: String, hasActiveObjective: Boolean): QuickRoute {
        return when {
            isContinuation(input, hasActiveObjective) -> QuickRoute.CONTINUE_OBJECTIVE
            isActionRequest(input) -> QuickRoute.EXECUTE_ACTION
            isQuestion(input) -> QuickRoute.ANSWER_QUESTION
            else -> QuickRoute.CLARIFY
        }
    }

    enum class QuickRoute { EXECUTE_ACTION, ANSWER_QUESTION, CONTINUE_OBJECTIVE, CLARIFY }
}
