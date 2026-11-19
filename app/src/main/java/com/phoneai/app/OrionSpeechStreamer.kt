package com.phoneai.app

/**
 * Turns the model's token stream into speakable chunks so Orion starts talking on the first sentence
 * instead of waiting for the whole reply. Code fences are held back (never spoken); the first chunk is allowed to
 * break early at a clause so the first word arrives fast, later chunks end at sentence boundaries.
 */
internal class OrionSpeechStreamer(private val maxSpokenChars: Int = 360, private val clock: () -> Long = System::currentTimeMillis) {
    private val buf = StringBuilder()
    private var emitted = 0
    private var lastFeed = 0L
    private var muted = false
    private var spokenChars = 0
    /** True once enough has been spoken: the rest of a long answer stays in chat, it is not read aloud. */
    var capped = false
        private set
    var spokeAny = false
        private set

    fun maxSpokenCharsValue(): Int = maxSpokenChars

    fun mute() { muted = true; buf.setLength(0) }
    fun reset() { buf.setLength(0); emitted = 0; spokeAny = false; muted = false; spokenChars = 0; capped = false }

    fun feed(delta: String): List<String> {
        val now = clock()
        if (lastFeed != 0L && now - lastFeed > 60_000) reset()
        lastFeed = now
        if (muted || capped || delta.isEmpty()) return emptyList()
        buf.append(delta)
        return drain(false)
    }

    fun flush(): List<String> = if (muted || capped) { buf.setLength(0); emptyList() } else drain(true)

    private fun drain(final: Boolean): List<String> {
        val out = ArrayList<String>()
        val closedFence = Regex("```[\\s\\S]*?```")
        while (true) {
            // Completed code blocks are dropped outright; they are never read aloud.
            val done = closedFence.find(buf)
            if (done != null) { buf.delete(done.range.first, done.range.last + 1); continue }
            // Never speak inside an unclosed code fence.
            val fenceCount = Regex("```").findAll(buf).count()
            val safeEnd = if (fenceCount % 2 == 1) buf.indexOf("```") else buf.length
            if (safeEnd <= 0) { if (final && fenceCount % 2 == 1) buf.setLength(0); break }
            val view = buf.substring(0, safeEnd)
            val cut = findCut(view, first = emitted == 0)
            if (cut <= 0) {
                if (final && view.isNotBlank()) { out += view.trim(); buf.delete(0, safeEnd); emitted++; spokeAny = true }
                break
            }
            val chunk = view.substring(0, cut).trim()
            buf.delete(0, cut)
            if (chunk.isNotEmpty()) {
                out += chunk; emitted++; spokeAny = true; spokenChars += chunk.length
                if (spokenChars >= maxSpokenChars) { capped = true; buf.setLength(0); break }
            }
        }
        return out
    }

    private fun findCut(s: String, first: Boolean): Int {
        val minLen = if (first) 10 else 24
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            val boundary = (c == '.' || c == '!' || c == '?' || c == '…') && (next == null || next.isWhitespace() || next == '"' || next == ')') ||
                c == '\n' && i > 0 && s[i - 1] == '\n'
            val digitDot = c == '.' && i > 0 && s[i - 1].isDigit() && next?.isDigit() == true
            if (boundary && !digitDot && next != null && i + 1 >= minLen) return i + 1
            if (first && (c == ',' || c == ':' || c == ';' || c == '—') && next != null && next.isWhitespace() && i + 1 >= 22) return i + 1
            i++
        }
        if (s.length >= 220) {
            val sp = s.lastIndexOf(' ', 200)
            if (sp > 80) return sp + 1
        }
        return -1
    }
}
