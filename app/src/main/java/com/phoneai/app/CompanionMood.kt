package com.phoneai.app

import java.util.Locale

/** What the desk companion feels and says, from the trader's live numbers. Pure, so the reactions are testable. */
internal object CompanionMood {
    enum class Mood { CALM, HAPPY, EXCITED, WORRIED, ALERT }
    class State(val mood: Mood, val line: String)

    class Input(
        val openPnl: Double, val realizedToday: Double, val equity: Double, val lossStreak: Int,
        val newsMinutes: Int?, val killSwitch: Boolean, val practice: Boolean, val positionsWithoutStop: Int, val hasData: Boolean
    )

    fun from(i: Input): State {
        if (!i.hasData) return State(Mood.CALM, "Practice mode. Add your Bybit key or open a practice trade and I will react to your numbers.")
        if (i.positionsWithoutStop > 0) return State(Mood.ALERT, "${i.positionsWithoutStop} open position${if (i.positionsWithoutStop == 1) " has" else "s have"} no stop loss. Set one now.")
        i.newsMinutes?.let { if (it in 0..30) return State(Mood.ALERT, "High-impact news in $it minutes. Be careful with new entries.") }
        if (i.killSwitch) return State(Mood.WORRIED, "Kill switch is on, so no new orders will pass. Take the time to reset.")
        if (i.lossStreak >= 3) return State(Mood.WORRIED, "${i.lossStreak} losses in a row. Step back, review the last trades before the next one.")
        val total = i.openPnl + i.realizedToday
        val pct = if (i.equity > 0) total / i.equity * 100.0 else 0.0
        val money = String.format(Locale.US, "%+.2f", total)
        return when {
            pct <= -1.5 -> State(Mood.WORRIED, "Down $money today (${"%.1f".format(Locale.US, pct)}%). Protect capital: no revenge trades.")
            pct >= 1.5 -> State(Mood.EXCITED, "Up $money today (${"%+.1f".format(Locale.US, pct)}%). Nice. Consider protecting it with a trailing stop.")
            pct >= 0.3 -> State(Mood.HAPPY, "Up $money today. Steady. Stick to the plan.")
            else -> State(Mood.CALM, if (i.practice) "Practice account is flat. A good time to review your playbook." else "Flat today. Patience is a position too.")
        }
    }

    /** Speak when the mood changes to something worth saying, and not again too soon. */
    fun shouldSpeak(prev: Mood?, cur: Mood, lastSpokeAt: Long, now: Long): Boolean {
        if (cur == Mood.CALM) return false
        val gap = now - lastSpokeAt
        return when {
            prev != cur -> gap > 5 * 60_000L
            cur == Mood.ALERT || cur == Mood.WORRIED -> gap > 30 * 60_000L
            else -> false
        }
    }
}

/** Text for the live PnL strip. */
internal object PnlStrip {
    fun text(openPnl: Double, realizedToday: Double, equity: Double, positions: Int, practice: Boolean): String {
        val pct = if (equity > 0) (openPnl + realizedToday) / equity * 100.0 else 0.0
        return (if (practice) "Practice · " else "") + "Open ${"%+.2f".format(Locale.US, openPnl)} · Today ${"%+.2f".format(Locale.US, realizedToday)}" +
            (if (equity > 0) " (${"%+.1f".format(Locale.US, pct)}%)" else "") + " · $positions position${if (positions == 1) "" else "s"}"
    }
}
