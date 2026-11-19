package com.phoneai.app

/**
 * Measured speed, per real turn. Marks are taken where the work actually happens (request start, context built,
 * first model token, first audible sound, end), so "is Orion fast?" has a number behind it instead of a feeling.
 * The ring lives in memory for the life of the process; [report] gives median and p90 per stage and per model.
 */
internal object OrionSpeedTrace {
    data class Turn(val model: String, val contextMs: Long, val firstTokenMs: Long, val firstAudioMs: Long, val totalMs: Long)

    private const val KEEP = 60
    private val lock = Any()
    private val done = ArrayDeque<Turn>()
    @Volatile private var start = 0L
    @Volatile private var contextAt = 0L
    @Volatile private var tokenAt = 0L
    @Volatile private var audioAt = 0L

    fun begin(now: Long = System.currentTimeMillis()) { start = now; contextAt = 0L; tokenAt = 0L; audioAt = 0L }

    /** First mark of each name wins; later ones in the same turn are ignored. */
    fun mark(stage: String, now: Long = System.currentTimeMillis()) {
        if (start == 0L) return
        when (stage) {
            "context" -> if (contextAt == 0L) contextAt = now
            "token" -> if (tokenAt == 0L) tokenAt = now
            "audio" -> if (audioAt == 0L) audioAt = now
        }
    }

    fun end(model: String, now: Long = System.currentTimeMillis()) {
        val s = start
        if (s == 0L) return
        fun d(t: Long) = if (t == 0L) -1L else (t - s).coerceAtLeast(0L)
        synchronized(lock) {
            done.addLast(Turn(model.substringAfterLast('/').take(40), d(contextAt), d(tokenAt), d(audioAt), (now - s).coerceAtLeast(0L)))
            while (done.size > KEEP) done.removeFirst()
        }
        start = 0L
    }

    fun turns(): List<Turn> = synchronized(lock) { done.toList() }
    fun clearForTest() { synchronized(lock) { done.clear() }; start = 0L }

    fun percentile(values: List<Long>, p: Double): Long {
        val v = values.filter { it >= 0 }.sorted()
        if (v.isEmpty()) return -1L
        return v[((v.size - 1) * p).toInt().coerceIn(0, v.size - 1)]
    }

    private fun fmt(ms: Long) = if (ms < 0) "n/a" else if (ms < 10_000) "%.1fs".format(ms / 1000.0) else "${ms / 1000}s"

    fun report(list: List<Turn> = turns()): String {
        if (list.isEmpty()) return "No turns measured yet in this process. Every turn from now on is timed."
        fun row(label: String, sel: (Turn) -> Long): String {
            val v = list.map(sel)
            return "$label: median ${fmt(percentile(v, 0.5))}, p90 ${fmt(percentile(v, 0.9))}"
        }
        val slowest = list.maxByOrNull { it.totalMs }!!
        val byModel = list.groupBy { it.model.ifBlank { "unknown" } }.entries.sortedByDescending { it.value.size }.take(4)
            .joinToString("; ") { (m, t) -> "$m ${t.size}x median ${fmt(percentile(t.map { it.totalMs }, 0.5))}" }
        return "SPEED over ${list.size} measured turns (this process):\n" +
            row("context built", { it.contextMs }) + "\n" + row("first word", { it.firstTokenMs }) + "\n" +
            row("first sound", { it.firstAudioMs }) + "\n" + row("whole turn", { it.totalMs }) + "\n" +
            "by model: $byModel\nslowest turn: ${fmt(slowest.totalMs)} on ${slowest.model.ifBlank { "unknown" }}."
    }
}
