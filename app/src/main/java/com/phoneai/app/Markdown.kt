package com.phoneai.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/**
 * Compact, dependency-free Markdown presentation for Orion's chat transcript.
 * Supports headings, lists, emphasis, inline code, links and fenced code blocks.
 */
object Markdown {
    /** Line-level cleanup shared by the renderer and tests: bullets and heading markers. */
    internal fun normalizeLines(text: String): String = text.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        when {
            t.startsWith("- ") || t.startsWith("* ") -> "• " + t.drop(2)
            Regex("^\\d+[.)]\\s+").containsMatchIn(t) -> "• " + t.replaceFirst(Regex("^\\d+[.)]\\s+"), "")
            else -> line
        }
    }

    fun render(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var inCode = false
        var codeStart = -1
        var hasRenderedLine = false

        lines.forEach { raw ->
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    if (hasRenderedLine) sb.append('\n')
                    inCode = true
                    codeStart = sb.length
                    hasRenderedLine = true
                } else {
                    applyCodeBlockStyle(sb, codeStart, sb.length)
                    inCode = false
                    codeStart = -1
                }
                // Fence markers and optional language names are presentation metadata, not content.
                return@forEach
            }

            if (hasRenderedLine) sb.append('\n')
            if (inCode) {
                sb.append(raw)
            } else {
                val normalized = normalizeLines(raw)
                val heading = Regex("^#{1,6}\\s+(.*)$").find(normalized)
                val start = sb.length
                appendInline(sb, heading?.groupValues?.get(1) ?: normalized)
                if (heading != null && sb.length > start) {
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.1f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            hasRenderedLine = true
        }

        if (inCode) applyCodeBlockStyle(sb, codeStart, sb.length)
        return sb
    }

    private fun applyCodeBlockStyle(sb: SpannableStringBuilder, start: Int, end: Int) {
        if (start < 0 || end <= start) return
        sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(BackgroundColorSpan(Theme.PANEL2), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Theme.TEXT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(0.92f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendInline(sb: SpannableStringBuilder, line: String) {
        val pattern = Regex(
            "\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)|\\*\\*(.+?)\\*\\*|\u0060([^\u0060]+)\u0060|(?<![*\\w])\\*([^*\\n]+)\\*(?!\\w)|(https?://[^\\s)]+)"
        )
        var last = 0
        for (m in pattern.findAll(line)) {
            sb.append(line, last, m.range.first)
            val start = sb.length
            when {
                m.groups[1] != null && m.groups[2] != null -> {
                    sb.append(m.groupValues[1])
                    sb.setSpan(URLSpan(m.groupValues[2].trimEnd('.', ',', ';')), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Theme.CYAN), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groups[3] != null -> {
                    sb.append(m.groupValues[3])
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groups[4] != null -> {
                    sb.append(m.groupValues[4])
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(Theme.PANEL2), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groups[5] != null -> {
                    sb.append(m.groupValues[5])
                    sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    val url = m.groupValues[6].trimEnd('.', ',', ';')
                    sb.append(url)
                    sb.setSpan(URLSpan(url), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(Theme.CYAN), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        sb.append(line, last, line.length)
    }
}

/** Fixed-fractional position sizing: risk a set percent of the account between entry and stop. */
object RiskCalc {
    class Result(val units: Double, val riskAmount: Double, val notional: Double, val stopPct: Double, val rrAtTarget: Double?)

    fun compute(balance: Double, riskPct: Double, entry: Double, stop: Double, target: Double? = null): Result? {
        if (balance <= 0 || riskPct <= 0 || riskPct > 100 || entry <= 0 || stop <= 0 || entry == stop) return null
        val riskAmount = balance * riskPct / 100.0
        val perUnit = Math.abs(entry - stop)
        val units = riskAmount / perUnit
        val rr = target?.takeIf { it > 0 }?.let { t ->
            val reward = if (entry > stop) t - entry else entry - t
            if (reward > 0) reward / perUnit else null
        }
        return Result(units, riskAmount, units * entry, perUnit / entry * 100.0, rr)
    }
}
