package com.phoneai.app

/**
 * Stage 4 loop / shared interaction contract. Every entry (chat, Talk, Command, voice, resume,
 * specialist return) runs observe -> recall -> reason -> plan -> act -> verify -> learn -> report.
 * This records which stages each turn really reached so skipped stages are visible, never assumed.
 */
internal object OrionEntityLoop {
    enum class Stage { OBSERVE, RECALL, REASON, PLAN, ACT, VERIFY, LEARN, REPORT }

    data class Turn(val surface: String, val reached: Set<Stage>, val order: List<Stage> = emptyList()) {
        /** True when recall happened before the first act, the mandatory retrieve-before-act rule. */
        val retrievedBeforeAct: Boolean get() { val r = order.indexOf(Stage.RECALL); val a = order.indexOf(Stage.ACT); return a < 0 || (r in 0 until a) }
        val missing: List<Stage> get() = Stage.values().filterNot { it in reached }
    }

    private val lock = Any()
    private val done = ArrayDeque<Turn>()
    private val active = ThreadLocal<MutableSet<Stage>?>()
    private val ordered = ThreadLocal<MutableList<Stage>?>()
    private val surfaceOf = ThreadLocal<String?>()

    fun begin(surface: String) { active.set(mutableSetOf(Stage.OBSERVE)); ordered.set(mutableListOf(Stage.OBSERVE)); surfaceOf.set(surface) }
    fun mark(stage: Stage) { if (active.get()?.add(stage) == true) ordered.get()?.add(stage) }

    fun end(): Turn? {
        val reached = active.get() ?: return null
        val t = Turn(surfaceOf.get() ?: "?", reached.toSet(), ordered.get()?.toList().orEmpty())
        active.set(null); ordered.set(null); surfaceOf.set(null)
        synchronized(lock) { done.addLast(t); while (done.size > 100) done.removeFirst() }
        return t
    }

    fun turns(): List<Turn> = synchronized(lock) { done.toList() }
    fun clearForTest() { synchronized(lock) { done.clear() }; active.set(null); ordered.set(null) }

    fun report(list: List<Turn> = turns()): String {
        if (list.isEmpty()) return "ENTITY LOOP: no turns recorded yet."
        val sb = StringBuilder("ENTITY LOOP (${list.size} turns): observe→recall→reason→plan→act→verify→learn→report\n")
        for (s in Stage.values()) {
            val n = list.count { s in it.reached }
            sb.append("  ${s.name.lowercase()}: $n/${list.size}\n")
        }
        val gaps = list.filter { it.missing.isNotEmpty() }.groupBy { it.surface }
            .map { (k, v) -> "$k skips " + v.flatMap { it.missing }.distinct().joinToString { it.name.lowercase() } }
        val late = list.count { !it.retrievedBeforeAct }
        sb.append("  retrieve-before-act: ${list.size - late}/${list.size}\n")
        if (gaps.isNotEmpty()) sb.append("Gaps: ").append(gaps.joinToString("; "))
        return sb.toString().trim()
    }
}
