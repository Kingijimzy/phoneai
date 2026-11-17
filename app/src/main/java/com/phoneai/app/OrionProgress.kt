package com.phoneai.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Live progress for anything that downloads or installs: a label, what is happening now, and sizes.
 * Parsed from real apt/npm/pip/curl output and from the runtime installers' byte counters, never invented.
 */
internal object OrionProgress {
    data class State(
        val active: Boolean = false, val label: String = "", val detail: String = "",
        val percent: Int = -1, val size: String = "", val ok: Boolean? = null
    )

    @Volatile var state = State()
        private set
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val bound = ThreadLocal<Long?>()

    fun listen(l: (State) -> Unit) { listeners += l; l(state) }
    fun unlisten(l: (State) -> Unit) { listeners -= l }

    /** Output of the tool step running on this thread also streams into the chat's live card. */
    fun bind(stepId: Long?) { bound.set(stepId) }

    private fun publish(s: State) { state = s; listeners.forEach { runCatching { it(s) } } }

    fun start(label: String) = publish(State(true, label, "Starting…"))

    fun done(ok: Boolean) = publish(state.copy(active = false, ok = ok, percent = if (ok) 100 else state.percent,
        detail = if (ok) "Finished" else "Did not finish"))

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024L * 1024 -> "%.0f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
        else -> "%.2f GB".format(n / 1073741824.0)
    }

    /** Runtime download counters: "Ubuntu root filesystem  45.2 MB / 120 MB". */
    fun bytesProgress(name: String, cur: Long, total: Long) {
        if (total <= 0) { publish(state.copy(active = true, detail = name)); return }
        publish(State(true, state.label.ifBlank { name }, name, (cur * 100 / total).toInt().coerceIn(0, 100), bytes(cur) + " / " + bytes(total)))
    }

    private val size = Regex("\\[?\\(?(\\d+(?:\\.\\d+)?\\s?[kKMG]i?B)\\)?\\]?")
    private val pct = Regex("(\\d{1,3})\\s?%")
    private val need = Regex("Need to get (\\S+ ?[kKMG]?B)")
    private val fetched = Regex("Fetched (\\S+ ?[kKMG]?B)")

    /** One output line to a new state; unrelated lines leave the state untouched. */
    fun parse(line: String, prev: State): State {
        val l = line.trim()
        if (l.isEmpty()) return prev
        need.find(l)?.let { return prev.copy(detail = "Downloading", size = "0 / " + it.groupValues[1]) }
        fetched.find(l)?.let { return prev.copy(detail = "Downloaded", size = it.groupValues[1], percent = maxOf(prev.percent, 60)) }
        val p = pct.find(l)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
        val s = size.find(l)?.groupValues?.get(1)
        return when {
            l.startsWith("Get:") -> prev.copy(detail = "Downloading " + l.substringAfter(' ').substringAfter(' ').split(' ').getOrElse(1) { "" }.ifBlank { "packages" }, size = s ?: prev.size)
            l.startsWith("Unpacking ") -> prev.copy(detail = "Unpacking " + l.removePrefix("Unpacking ").substringBefore(' '), percent = maxOf(prev.percent, 70))
            l.startsWith("Setting up ") -> prev.copy(detail = "Setting up " + l.removePrefix("Setting up ").substringBefore(' '), percent = maxOf(prev.percent, 90))
            l.startsWith("Collecting ") -> prev.copy(detail = l.take(60))
            l.startsWith("Downloading ") -> prev.copy(detail = l.take(60), size = s ?: prev.size)
            l.startsWith("added ") || l.startsWith("Successfully installed") -> prev.copy(detail = l.take(60), percent = 95)
            l.startsWith("E:") || l.startsWith("npm ERR") || l.startsWith("ERROR") -> prev.copy(detail = l.take(80))
            p != null -> prev.copy(percent = p, size = s ?: prev.size, detail = prev.detail.ifBlank { "Working" })
            else -> prev
        }
    }

    fun feed(line: String) {
        val next = parse(line, state)
        if (next != state) publish(next)
        bound.get()?.let { id -> OrionRunLog.append(id, line) }
    }

    /** Streams a script through the active runtime with live progress, then returns its output. */
    fun run(c: android.content.Context, label: String, script: String, where: String): String {
        start(label)
        val out = ShellRunner.runStreaming(c, script, where) { feed(it) }
        done(!out.startsWith("error:") && !out.contains("E: ") && !Regex("(INSTALL_EXIT|exit)=[1-9]").containsMatchIn(out))
        return out
    }
}
