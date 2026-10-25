package com.phoneai.app

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Tiny chat-bubble markdown: **bold**, *italic*, `code`, # headings, - bullets. */
object Markdown {
    /** Line-level cleanup shared by the renderer and tests: bullets and heading markers. */
    internal fun normalizeLines(text: String): String = text.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        when {
            t.startsWith("- ") || t.startsWith("* ") -> "• " + t.drop(2)
            else -> line
        }
    }

    fun render(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        for ((i, raw) in normalizeLines(text).lines().withIndex()) {
            if (i > 0) sb.append('\n')
            val heading = Regex("^#{1,6}\\s+(.*)$").find(raw)
            val start = sb.length
            appendInline(sb, heading?.groupValues?.get(1) ?: raw)
            if (heading != null) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.1f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, line: String) {
        val pattern = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|(?<![*\\w])\\*([^*\\n]+)\\*(?!\\w)")
        var last = 0
        for (m in pattern.findAll(line)) {
            sb.append(line, last, m.range.first)
            val start = sb.length
            when {
                m.groups[1] != null -> { sb.append(m.groupValues[1]); sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                m.groups[2] != null -> { sb.append(m.groupValues[2]); sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                else -> { sb.append(m.groupValues[3]); sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
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
