package com.phoneai.app

/**
 * Live, in-memory activity feed of what Orion is doing: phases, tool steps, results, approvals, recovery,
 * plans and extension work. The Soul Engine screen subscribes to it, so it updates by itself instead of
 * needing a refresh button. Entries are short, redacted one-liners; full content stays in chat and checkpoints.
 * No Android types, so the rules are unit tested.
 */
internal object SoulEventLog {
    enum class Kind { PHASE, STEP, RESULT, APPROVAL, RECOVERY, PLAN, SAY, SYSTEM }

    data class Event(val seq: Long, val atMillis: Long, val kind: Kind, val text: String)

    const val CAPACITY = 300
    const val MAX_TEXT = 160

    private val lock = Any()
    private val events = ArrayDeque<Event>()
    private var nextSeq = 1L
    private val listeners = LinkedHashSet<() -> Unit>()

    @Volatile var phase: String = "idle"
        private set
    @Volatile var lastActivityAt: Long = 0L
        private set

    private val secretPatterns = listOf(
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}"),
        Regex("\\b[A-Za-z0-9_\\-]{32,}\\b"),
        Regex("\\+?\\d[\\d ()\\-]{8,}\\d")
    )

    fun redact(text: String): String {
        var out = text.replace(Regex("\\s+"), " ").trim()
        secretPatterns.forEach { out = it.replace(out, "[redacted]") }
        return if (out.length > MAX_TEXT) out.take(MAX_TEXT - 1) + "…" else out
    }

    fun record(kind: Kind, text: String, now: Long = System.currentTimeMillis()): Event? {
        val clean = redact(text)
        if (clean.isEmpty()) return null
        val event: Event
        val toNotify: List<() -> Unit>
        synchronized(lock) {
            event = Event(nextSeq++, now, kind, clean)
            events.addLast(event)
            while (events.size > CAPACITY) events.removeFirst()
            lastActivityAt = now
            toNotify = listeners.toList()
        }
        toNotify.forEach { runCatching { it() } }
        return event
    }

    fun recent(limit: Int = 30): List<Event> = synchronized(lock) { events.toList().takeLast(limit.coerceAtLeast(0)) }
    fun since(seq: Long): List<Event> = synchronized(lock) { events.filter { it.seq > seq } }
    fun size(): Int = synchronized(lock) { events.size }
    fun addListener(l: () -> Unit) { synchronized(lock) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(lock) { listeners.remove(l) } }
    fun clear() { synchronized(lock) { events.clear(); phase = "idle" } }

    fun setPhase(p: String, now: Long = System.currentTimeMillis()) {
        if (p == phase) return
        phase = p
        record(Kind.PHASE, "Phase: $p", now)
    }

    /** Turns a chat note (as Agent.kt emits it) into a feed entry, or null when it is not worth showing. */
    fun fromNote(note: String): Pair<Kind, String>? {
        val lines = note.lineSequence().toList()
        val head = lines.firstOrNull().orEmpty()
        val body = lines.drop(1).joinToString(" ").trim()
        return when {
            head == "[TASK:RUNNING]" -> Kind.STEP to body
            head == "[TASK:PLANNED]" -> Kind.PLAN to "Plan: " + lines.drop(1).joinToString("  ")
            head == "[TASK:APPROVAL]" -> Kind.APPROVAL to "Waiting for your approval"
            head == "[TASK:APPROVED]" -> Kind.APPROVAL to "You approved the action"
            head == "[TASK:DENIED]" -> Kind.APPROVAL to "You did not approve; the action did not run"
            head == "[TASK:RECOVER]" -> Kind.RECOVERY to body
            head == "[TASK:BLOCKED]" -> Kind.RECOVERY to "Paused: $body"
            head == "[TASK:CANCELLED]" -> Kind.SYSTEM to "Stopped: $body"
            head.endsWith(":FAILED]") -> Kind.RESULT to "Failed — " + (lines.getOrNull(1).orEmpty())
            head.endsWith(":RESULT]") -> Kind.RESULT to "Done — " + (lines.getOrNull(1).orEmpty())
            head.startsWith("[TASK:") -> Kind.SYSTEM to (head.removePrefix("[TASK:").removeSuffix("]").lowercase().replace('_', ' ') + if (body.isNotEmpty()) ": $body" else "")
            else -> null
        }
    }

    fun glyph(kind: Kind): String = when (kind) {
        Kind.PHASE -> "◌"; Kind.STEP -> "▸"; Kind.RESULT -> "✓"; Kind.APPROVAL -> "?"
        Kind.RECOVERY -> "↻"; Kind.PLAN -> "≡"; Kind.SAY -> "“"; Kind.SYSTEM -> "·"
    }

    /** "5s ago", "3m ago", "2h ago". */
    fun ago(atMillis: Long, now: Long): String {
        val s = ((now - atMillis) / 1000).coerceAtLeast(0)
        return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60}m ago"; else -> "${s / 3600}h ago" }
    }
}

/** Wraps the service's UI so every agent note, phase and reply also lands in the live feed. Behaviour is unchanged. */
internal class SoulRecordingUi(private val inner: AgentUi) : AgentUi {
    override fun say(text: String) { SoulEventLog.record(SoulEventLog.Kind.SAY, "Orion: $text"); inner.say(text) }
    override fun status(text: String) = inner.status(text)
    override fun note(text: String) {
        SoulEventLog.fromNote(text)?.let { (kind, line) -> SoulEventLog.record(kind, line) }
        inner.note(text)
    }
    override fun stream(text: String) = inner.stream(text)
    override fun phase(p: String) { SoulEventLog.setPhase(p); inner.phase(p) }
    override fun ask(question: String): Boolean = inner.ask(question)
    override fun askUser(question: String, options: List<String>, access: String): String = inner.askUser(question, options, access)
}
