package com.phoneai.app

/** What makes the companion feel alive: when it reacts, and what it says when you arrive. Pure, so it is testable. */
internal object CompanionLife {
    enum class Reaction { NONE, CELEBRATE, FLINCH, ALARM }

    /** A realised result since the last look, or a new alert, is worth a visible reaction. */
    fun reactionTo(prevRealized: Double?, realized: Double, equity: Double, prevMood: CompanionMood.Mood?, mood: CompanionMood.Mood): Reaction {
        if (mood == CompanionMood.Mood.ALERT && prevMood != CompanionMood.Mood.ALERT && prevMood != null) return Reaction.ALARM
        if (prevRealized == null) return Reaction.NONE
        val delta = realized - prevRealized
        val floor = maxOf(0.01, equity * 0.001)
        return when {
            delta >= floor -> Reaction.CELEBRATE
            delta <= -floor -> Reaction.FLINCH
            else -> Reaction.NONE
        }
    }

    fun greeting(hour: Int, awayMinutes: Long, positions: Int, mood: CompanionMood.Mood, newsMinutes: Int?): String {
        val period = when {
            hour < 5 -> "Still up"
            hour < 12 -> "Good morning"
            hour < 18 -> "Good afternoon"
            else -> "Good evening"
        }
        val sb = StringBuilder(period).append(". ")
        if (awayMinutes >= 240) sb.append("Welcome back. ")
        if (positions > 0) sb.append("You have $positions open position${if (positions == 1) "" else "s"}. ")
        sb.append(when (mood) {
            CompanionMood.Mood.ALERT -> "Something needs your attention right now."
            CompanionMood.Mood.WORRIED -> "Let's be careful today."
            CompanionMood.Mood.EXCITED -> "You are having a strong day."
            CompanionMood.Mood.HAPPY -> "Things are going steadily."
            CompanionMood.Mood.CALM -> "The market is quiet and I am watching it."
        })
        if (newsMinutes != null && newsMinutes in 0..180) sb.append(" High-impact news in ").append(if (newsMinutes < 60) "${newsMinutes}m." else "${newsMinutes / 60}h.")
        return sb.toString()
    }
}

/** The chat's quick actions, grouped so they fit on a screen. */
internal object ChatGroups {
    class Chip(val label: String, val prompt: String? = null, val action: String? = null)
    class Group(val name: String, val chips: List<Chip>)

    val coach = listOf(
        Group("Market", listOf(
            Chip("Find setups", "Find me the best trade setups on my watchlist right now and judge them against my strategy."),
            Chip("Outlook", "Give me your outlook for my watchlist: bias, key levels and what would invalidate it."),
            Chip("Briefing", "Give me my trading briefing: outlook for my watchlist, the best setups, and one risk to watch today."),
            Chip("News & sessions", "What high-impact news and which sessions matter for my playbook pairs in the next 24 hours?"),
            Chip("Forecast", "Give me a forecast for the main pair in my playbook using my strategy, its simulated record, the news calendar and headlines."),
            Chip("Quote", action = "quote")
        )),
        Group("Positions", listOf(
            Chip("Positions", "Check my open positions: stops, risk, news ahead, and what I should do about each."),
            Chip("Habits", "Review my trading habits from my synced trades: overtrading, revenge trades, bad hours, size after losses. Be direct and give me one rule."),
            Chip("Review a trade", action = "review"),
            Chip("Risk calculator", action = "risk"),
            Chip("Weekly review", "Review my week: use my journal stats and track record, name my best and worst habits, and give me one rule to add.")
        )),
        Group("Strategy", listOf(
            Chip("Match playbook", "Match my playbook now: scan every instrument in it, rank what fits my rules, and warn me about news or session problems."),
            Chip("Simulate", "Simulate my playbook on recent history for the pairs in it and tell me honestly whether the numbers show an edge."),
            Chip("My playbook", "List my saved strategies with their recorded results and tell me which one deserves more trust and which should be dropped."),
            Chip("Improve it", "Look at my results and suggest one specific change to my best strategy, and say how I should test it before trusting it.")
        )),
        Group("Chain", listOf(
            Chip("Funding", "What do funding rates and open interest say about my watchlist right now, and is the crowd leaning one way?"),
            Chip("Memecoins & wallets", "Check my memecoin watchlist and watched wallets and tell me what needs attention, including any rug or dump warning signs.")
        )),
        Group("More", listOf(
            Chip("Desk", action = "desk"),
            Chip("Brain", action = "brain"),
            Chip("Voice", action = "voice"),
            Chip("Talk", action = "talk"),
            Chip("Mic", action = "mic"),
            Chip("Learned", action = "learned"),
            Chip("Clear", action = "clear")
        ))
    )
}
