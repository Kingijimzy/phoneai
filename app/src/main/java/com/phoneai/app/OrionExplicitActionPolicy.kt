package com.phoneai.app

/**
 * Deterministic routing for explicit, unambiguous tool requests.
 *
 * The model cannot be trusted to emit the ACTION sentinel for "create a subagent":
 * in practice it answered conversationally, then drifted to a web search, then checked
 * GitHub on "continue". Requests this explicit are routed without asking the model.
 */
internal object OrionExplicitActionPolicy {
    data class CreateAgentRequest(val topic: String)

    private val createAgent = Regex(
        "(?i)\\b(?:create[sd]?|make|add|set\\s*up|build|spawn|start)\\s+(?:me\\s+)?(?:a|an|the)?\\s*(?:new\\s+)?" +
            "(?:sub-?agent|agent|specialist)\\b\\s*" +
            "(?:for|called|named|about|to\\s+(?:research|track|watch|handle|study))?\\s*(.*)$"
    )
    private val pronounTopic = Regex("(?i)^(that|this|it|them|those)\\b")

    fun createAgentRequest(command: String): CreateAgentRequest? {
        val match = createAgent.find(command.trim()) ?: return null
        return CreateAgentRequest(match.groupValues[1].trim())
    }

    fun isPronounTopic(topic: String): Boolean = pronounTopic.containsMatchIn(topic.trim())

    /** Keep only the noun phrase: "that bro, what's wrong" -> "that bro". */
    fun cleanTopic(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("(?i)^about\\s+"), "")
        t = t.substringBefore(',').substringBefore(';').substringBefore('.').substringBefore('?').trim()
        t = t.replace(Regex("(?i)\\s+(bro|man|please|orion|ok|okay)\\s*$"), "").trim()
        return t.take(80)
    }

    fun slug(topic: String): String {
        val base = topic.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val cleaned = base.ifBlank { "research" }.take(20)
        return if (cleaned.length < 3) (cleaned + "agent").take(20) else cleaned
    }

    fun displayName(topic: String): String =
        topic.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .take(40).ifBlank { "Research Specialist" }

    fun tagline(topic: String): String = "Tracks $topic".take(80)

    fun persona(topic: String): String =
        ("You are Orion's $topic specialist sub-agent. Research $topic thoroughly using read-only tools " +
            "(live_web_search, live_news, fetch_url, live_sports), compare sources, date every claim, cite source " +
            "URLs, and separate verified facts from uncertainty. Never claim an action happened without evidence. " +
            "Return a concise brief: the key developments, why they matter, and what to watch next.").take(700)
}
