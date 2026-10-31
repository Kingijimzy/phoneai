/*
 * Adapted from Hermes Agent:
 * https://github.com/NousResearch/hermes-agent
 * Upstream file: agent/repetition_guard.py
 * Copyright (c) 2025 Nous Research
 *
 * Kotlin adaptation for PhoneAI Android response validation. The conservative thresholds and
 * repetition-detection strategy follow the upstream MIT-licensed implementation.
 * Licensed under the MIT License; see licenses/HERMES-MIT-LICENSE.txt.
 */
package com.phoneai.app

/**
 * Detects runaway repeated model output without rejecting ordinary short answers or intentionally
 * repeated content. This is a bounded, dependency-free Kotlin port of Hermes Agent's guard.
 */
internal object HermesRepetitionGuard {
    private const val MIN_FRAGMENT_LENGTH = 400
    private const val REPEAT_WINDOW = 60
    private const val MIN_REPEAT_COUNT = 5
    private const val DOMINANCE_RATIO = 0.5
    private const val MAX_ANCHOR_SAMPLES = 32
    private const val MAX_ANCHOR_MATCHES = 8
    private const val RUNAWAY_DISTINCT_LINE_RATIO = 0.5
    private const val STOP_PATH_MIN_CHARS = 16_000
    private const val STREAM_TAIL_CHARS = 4 * STOP_PATH_MIN_CHARS

    /**
     * Bounded incremental watcher ported from Hermes RunawayStreamWatch. Checks become less
     * frequent as the stream grows and retained text is trimmed to a fixed tail window.
     */
    class RunawayStreamWatch {
        private val parts = StringBuilder()
        private var chars = 0
        private var nextCheck = STOP_PATH_MIN_CHARS

        @Synchronized
        fun feed(delta: String?): Boolean {
            if (delta.isNullOrEmpty()) return false
            parts.append(delta)
            chars += delta.length
            if (chars < nextCheck) return false
            nextCheck = chars + minOf(chars, STREAM_TAIL_CHARS)
            val current = parts.toString()
            val tail = if (current.length > STREAM_TAIL_CHARS) current.takeLast(STREAM_TAIL_CHARS) else current
            parts.setLength(0)
            parts.append(tail)
            return isRunawayRepetition(tail)
        }
    }

    fun isRepetitionDominated(text: String?): Boolean {
        if (text == null || text.length < MIN_FRAGMENT_LENGTH) return false
        val n = text.length
        if (lineRepetitionDominated(text, n)) return true
        return windowCountDominated(text, n) || periodicRunDominated(text, n)
    }

    /** Stricter completion-path check: repeated windows must also have a runaway line shape. */
    fun isRunawayRepetition(text: String?): Boolean {
        if (!isRepetitionDominated(text)) return false
        val lines = text.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < MIN_REPEAT_COUNT) return true
        return lines.toSet().size <= lines.size * RUNAWAY_DISTINCT_LINE_RATIO
    }

    private fun windowCountDominated(text: String, n: Int): Boolean {
        val needed = maxOf(MIN_REPEAT_COUNT, kotlin.math.ceil(n * DOMINANCE_RATIO / REPEAT_WINDOW).toInt())
        val counts = HashMap<String, Int>()
        for (i in 0..(n - REPEAT_WINDOW)) {
            val key = text.substring(i, i + REPEAT_WINDOW)
            val count = (counts[key] ?: 0) + 1
            if (count >= needed) return true
            counts[key] = count
        }
        return false
    }

    private fun periodicRunDominated(text: String, n: Int): Boolean {
        val maxStart = n - REPEAT_WINDOW
        if (maxStart < 1) return false
        val sampleStep = maxOf(1, (maxStart + MAX_ANCHOR_SAMPLES - 2) / (MAX_ANCHOR_SAMPLES - 1))
        val starts = (0..maxStart step sampleStep).toMutableList()
        if (starts.last() != maxStart) starts.add(maxStart)
        val rejected = mutableListOf<Triple<Int, Int, Int>>()
        for (start in starts) {
            val anchor = text.substring(start, start + REPEAT_WINDOW)
            var searchFrom = start + 1
            repeat(MAX_ANCHOR_MATCHES) {
                val match = text.indexOf(anchor, searchFrom)
                if (match < 0) return@repeat
                val period = match - start
                searchFrom = match + 1
                if (rejected.any { (lo, hi, priorPeriod) ->
                        lo <= start && start < hi && period % priorPeriod == 0
                    }) return@repeat
                val (left, right) = expandRun(text, n, start, period, REPEAT_WINDOW)
                if (right - left >= MIN_REPEAT_COUNT * period &&
                    right - left >= n * DOMINANCE_RATIO) return true
                rejected.add(Triple(left, right, period))
            }
        }
        return false
    }

    private fun expandRun(text: String, n: Int, start: Int, period: Int, matched: Int): Pair<Int, Int> {
        var left = start
        while (left > 0 && text[left - 1] == text[left - 1 + period]) left--
        var right = start + matched
        while (right + period < n && text[right] == text[right + period]) right++
        return left to (right + period)
    }

    private fun lineRepetitionDominated(text: String, n: Int): Boolean {
        val counts = HashMap<String, Int>()
        for (line in text.lineSequence()) {
            val normalized = line.trim()
            if (normalized.isNotEmpty()) counts[normalized] = (counts[normalized] ?: 0) + 1
        }
        return counts.any { (line, count) ->
            count >= MIN_REPEAT_COUNT && count * line.length >= n * DOMINANCE_RATIO
        }
    }
}
