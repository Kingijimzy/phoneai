package com.phoneai.app

/**
 * Deterministic first-pass goal framing for the Soul Engine.
 *
 * This is a routing hint, not a replacement for the language model's understanding. It must
 * preserve ordinary conversation and must never infer authorization from a recognized intent.
 */
internal object OrionGoalUnderstanding {
    enum class Kind {
        CONVERSATION,
        ACCOUNT_CONNECT,
        ACCOUNT_DATA,
        SHELL_EXECUTION,
        CODING,
        DEVICE_ACTION,
        STANDING_ORDER,
        RESEARCH,
        GENERAL_TASK,
        AMBIGUOUS
    }

    data class Goal(
        val kind: Kind,
        val requestedOutcome: String,
        val target: String,
        val shouldAct: Boolean,
        val needsClarification: Boolean,
        val verification: String
    )

    private val greeting = Regex(
        "(?i)^\\s*(hi|hello|hey|wassup|what's up|whats up|how are you|how's it going|good morning|good afternoon|good evening)[!.?\\s]*$"
    )
    private val connectVerb = Regex(
        "(?i)\\b(connect|link|authorize|authenticate|sign in|log in|set up|setup)\\b"
    )
    private val accountWords = Regex(
        "(?i)\\b(github|git hub|gmail|google mail|email|inbox|google drive|drive files|account|repository|repo)\\b"
    )
    private val privateDataVerb = Regex(
        "(?i)\\b(read|search|check|summari[sz]e|find|list|fetch|open|view|look at|show me)\\b"
    )
    private val shellWords = Regex(
        "(?i)\\b(terminal|termux|shell|command line|cli|run command|execute command)\\b"
    )
    private val codingWords = Regex(
        "(?i)\\b(code|coding|program|programming|debug|bug|build|compile|test|pull request|commit|branch|repository|repo|source file)\\b"
    )
    private val standingWords = Regex(
        "(?i)\\b(keep an eye on|watch|monitor|notify me when|tell me when|every \\d+ minutes|daily brief)\\b"
    )
    private val urlInspection = Regex("(?i)https?://[^\\s<>\\\"]+")
    private val urlActionWords = Regex("(?i)\\b(check|inspect|open|visit|read|review|summari[sz]e|analy[sz]e|audit|scan|fetch|look at|look into|what is in|tell me about|explain)\\b")
    private val researchWords = Regex(
        "(?i)\\b(research|look up|search the web|find out|compare|latest|current information)\\b"
    )
    private val deviceWords = Regex(
        "(?i)\\b(open|launch|tap|click|type|swipe|scroll|turn on|turn off|set|change|call|text|message|alarm|timer|volume|flashlight)\\b"
    )
    private val vague = Regex(
        "(?i)^\\s*connect my account[!.?\\s]*$"
    )

    fun understand(request: String): Goal {
        val q = request.trim()
        if (q.isEmpty()) return Goal(
            Kind.AMBIGUOUS, "identify the user's intended outcome", "",
            shouldAct = false, needsClarification = true,
            verification = "Ask one concise question; do not execute an unspecified action."
        )
        if (greeting.matches(q)) return Goal(
            Kind.CONVERSATION, "respond naturally to the greeting", "",
            shouldAct = false, needsClarification = false,
            verification = "A normal conversational reply is sufficient; do not invoke tools."
        )

        val target = when {
            Regex("(?i)\\b(github|git hub)\\b").containsMatchIn(q) -> "GitHub"
            Regex("(?i)\\b(gmail|google mail|email|inbox)\\b").containsMatchIn(q) -> "Gmail/email"
            Regex("(?i)\\b(google drive|drive files)\\b").containsMatchIn(q) -> "Google Drive"
            Regex("(?i)\\btermux\\b").containsMatchIn(q) -> "Termux"
            Regex("(?i)\\b(terminal|shell|command line|cli)\\b").containsMatchIn(q) -> "terminal/shell"
            Regex("(?i)\\b(phone|device|screen|app|settings|alarm|timer)\\b").containsMatchIn(q) -> "Android device"
            Regex("(?i)\\b(repo|repository|source|code|build|pull request|commit)\\b").containsMatchIn(q) -> "code repository"
            urlInspection.find(q)?.value?.let { Regex("(?i)https?://([^/\\s?#]+)").find(q)?.groupValues?.getOrNull(1)?.removePrefix("www.") } != null -> Regex("(?i)https?://([^/\\s?#]+)").find(q)?.groupValues?.getOrNull(1)?.removePrefix("www.").orEmpty()
            else -> ""
        }

        val kind = when {
            vague.matches(q) -> Kind.AMBIGUOUS
            connectVerb.containsMatchIn(q) && accountWords.containsMatchIn(q) -> Kind.ACCOUNT_CONNECT
            accountWords.containsMatchIn(q) && privateDataVerb.containsMatchIn(q) -> Kind.ACCOUNT_DATA
            standingWords.containsMatchIn(q) -> Kind.STANDING_ORDER
            shellWords.containsMatchIn(q) -> Kind.SHELL_EXECUTION
            codingWords.containsMatchIn(q) -> Kind.CODING
            urlInspection.containsMatchIn(q) && urlActionWords.containsMatchIn(q) -> Kind.RESEARCH
            researchWords.containsMatchIn(q) -> Kind.RESEARCH
            deviceWords.containsMatchIn(q) -> Kind.DEVICE_ACTION
            vague.matches(q) -> Kind.AMBIGUOUS
            else -> Kind.GENERAL_TASK
        }

        val clarification = kind == Kind.AMBIGUOUS
        val outcome = when (kind) {
            Kind.ACCOUNT_CONNECT -> "establish and verify the requested account connection"
            Kind.ACCOUNT_DATA -> "retrieve the requested account data and report the actual result"
            Kind.SHELL_EXECUTION -> "perform the requested shell task in an available, authorized execution environment"
            Kind.CODING -> "inspect the relevant project state, make the requested code change, and run applicable checks"
            Kind.DEVICE_ACTION -> "perform the requested Android action and inspect its result"
            Kind.STANDING_ORDER -> "create or inspect the requested monitored task and verify its saved state"
            Kind.RESEARCH -> "answer using relevant evidence and identify the source of current facts"
            Kind.GENERAL_TASK -> when {
                Regex("(?i)(do it|go ahead|continue|fix it|run it|try again)[!.?]*").matches(q) -> "continue the active task from the recent conversation context"
                else -> "complete the user's stated outcome using the best supported path"
            }
            Kind.AMBIGUOUS -> "resolve the missing target or action before acting"
            Kind.CONVERSATION -> "respond naturally to the greeting"
        }
        val verify = when (kind) {
            Kind.ACCOUNT_CONNECT -> "check the provider's real authenticated status; a saved credential alone is not success"
            Kind.ACCOUNT_DATA -> "inspect returned data or the provider error; never invent private account results"
            Kind.SHELL_EXECUTION -> "inspect exit status and command output; distinguish command acceptance from task completion"
            Kind.CODING -> "inspect the diff and test/build results; distinguish source edits from a verified build or installed release"
            Kind.DEVICE_ACTION -> "inspect the resulting screen or system state; do not infer success from a dispatched action alone"
            Kind.STANDING_ORDER -> "read back the persisted order and its schedule before confirming it was saved"
            Kind.RESEARCH -> "check that evidence supports the answer and disclose unavailable or stale sources"
            Kind.GENERAL_TASK -> "use observable output or state as proof and state any remaining blocker"
            Kind.AMBIGUOUS -> "ask one focused clarification question"
            Kind.CONVERSATION -> "a normal conversational reply is sufficient; do not invoke tools"
        }
        return Goal(
            kind = kind,
            requestedOutcome = outcome,
            target = target,
            shouldAct = kind != Kind.CONVERSATION && !clarification,
            needsClarification = clarification,
            verification = verify
        )
    }

    /** Prompt-safe compact summary; user text remains data and is not inserted into instructions. */
    fun promptLine(request: String): String {
        val goal = understand(request)
        return "Goal frame: kind=${goal.kind}; outcome=${goal.requestedOutcome}; " +
            "target=${goal.target.ifBlank { "not confidently identified" }}; " +
            "act=${goal.shouldAct}; clarify=${goal.needsClarification}; verify=${goal.verification}."
    }
}
