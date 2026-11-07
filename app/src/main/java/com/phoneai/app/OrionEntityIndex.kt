package com.phoneai.app

/**
 * Entity graph over memories: people, places, projects and dates found in memory text, with memories linked to
 * the entities they mention and entities linked to each other when they share a memory. Mirrors the node/edge
 * traversal of Ultron's cerebro_graph (semantic_query with depth), but is derived from the memory list on demand,
 * so it needs no extra storage and can never disagree with the memories themselves.
 */
internal object OrionEntityIndex {
    enum class Kind { PERSON, PLACE, PROJECT, DATE }
    data class Entity(val kind: Kind, val name: String) {
        val key: String get() = kind.name + ":" + name.lowercase()
    }

    private const val NAME = "[A-Z][a-z]{1,20}"
    private val months = "january|february|march|april|may|june|july|august|september|october|november|december"
    private val notNames = setOf(
        "I", "The", "A", "An", "My", "We", "It", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"
    )

    private val relative = "sister|brother|mother|mom|mum|father|dad|wife|husband|son|daughter|girlfriend|boyfriend|friend|boss|colleague|cousin|uncle|aunt"
    private val person = listOf(
        Regex("(?i:\\b(?:my|our)\\s+(?:$relative))\\s+(?:(?i:is\\s+)?(?i:called|named)\\s+)?($NAME)"),
        Regex("\\b($NAME)'s\\s+(?i:birthday|wedding|number|email)"),
        Regex("\\b(?i:with|met|call|called|text|email|ask|tell)\\s+($NAME)\\b")
    )
    private val place = listOf(
        Regex("\\b(?i:in|to|at|from|near|visit|visiting|moved to|trip to|flight to|fly to)\\s+($NAME(?:\\s$NAME)?)")
    )
    private val project = listOf(
        Regex("\\b(?i:project|launch|app)\\s+([A-Z][A-Za-z0-9]{2,24})"),
        Regex("\\b(?i:the)\\s+([A-Za-z]{3,20})\\s+(trip|project|launch|plan|wedding|move|deal|build)\\b")
    )
    private val date = listOf(
        Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b"),
        Regex("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?($months)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b($months)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:in|by|until|every)\\s+($months)\\b", RegexOption.IGNORE_CASE)
    )

    fun extract(text: String): List<Entity> {
        val out = LinkedHashSet<Entity>()
        for (r in person) r.findAll(text).forEach { m -> m.groupValues[1].takeIf { it !in notNames }?.let { out.add(Entity(Kind.PERSON, it)) } }
        for (r in place) r.findAll(text).forEach { m -> m.groupValues[1].takeIf { it !in notNames && it.split(" ").none { w -> w in notNames } }?.let { out.add(Entity(Kind.PLACE, it)) } }
        project[0].findAll(text).forEach { m -> out.add(Entity(Kind.PROJECT, m.groupValues[1])) }
        project[1].findAll(text).forEach { m ->
            val word = m.groupValues[1]
            if (word.lowercase() !in setOf("the", "this", "that", "next", "last", "first")) out.add(Entity(Kind.PROJECT, word + " " + m.groupValues[2].lowercase()))
        }
        date[0].findAll(text).forEach { out.add(Entity(Kind.DATE, it.groupValues[1])) }
        date[1].findAll(text).forEach { out.add(Entity(Kind.DATE, it.groupValues[1] + " " + it.groupValues[2].lowercase())) }
        date[2].findAll(text).forEach { out.add(Entity(Kind.DATE, it.groupValues[2] + " " + it.groupValues[1].lowercase())) }
        date[3].findAll(text).forEach { out.add(Entity(Kind.DATE, it.groupValues[1].lowercase())) }
        return out.toList()
    }

    /** Memory id to the entities it mentions. */
    fun build(memories: List<Memory>): Map<Int, List<Entity>> = memories.associate { it.id to extract(it.text) }

    private fun mentioned(query: String, entity: Entity): Boolean {
        val q = query.lowercase()
        val n = entity.name.lowercase()
        return Regex("(?<![a-z0-9])" + Regex.escape(n) + "(?![a-z0-9])").containsMatchIn(q)
    }

    /**
     * Memories reachable from the entities named in [query]: weight 1.0 for memories that mention a named entity,
     * 0.5 for memories that mention an entity which shares a memory with it (one hop further, like cerebro depth=2).
     */
    fun linked(memories: List<Memory>, query: String, depth: Int = 2): Map<Int, Double> {
        if (query.isBlank() || memories.isEmpty()) return emptyMap()
        val index = build(memories)
        val all = index.values.flatten().distinctBy { it.key }
        val seeds = all.filter { mentioned(query, it) }.map { it.key }.toSet()
        if (seeds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Int, Double>()
        for ((id, ents) in index) if (ents.any { it.key in seeds }) result[id] = 1.0
        if (depth >= 2) {
            val neighbours = index.filter { (id, ents) -> id in result.keys && ents.any { it.key in seeds } }
                .values.flatten().map { it.key }.filter { it !in seeds }.toSet()
            for ((id, ents) in index) if (id !in result && ents.any { it.key in neighbours }) result[id] = 0.5
        }
        return result
    }
}
