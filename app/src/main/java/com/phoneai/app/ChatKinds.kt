package com.phoneai.app

/** The kinds of cards the trading chat shows, each with its own label and colour. Pure. */
internal object ChatKinds {
    enum class Kind(val label: String, val color: Int) {
        SETUPS("SETUPS", 0xFF5AA9FF.toInt()),
        OUTLOOK("OUTLOOK", 0xFF7C9CFF.toInt()),
        BRIEFING("BRIEFING", 0xFF9AA7FF.toInt()),
        NEWS("NEWS & SESSIONS", 0xFFFFC857.toInt()),
        FORECAST("FORECAST", 0xFF3FD0C9.toInt()),
        POSITIONS("POSITIONS", 0xFF4CD08A.toInt()),
        HABITS("HABITS", 0xFFB08CFF.toInt()),
        REVIEW("TRADE REVIEW", 0xFFFF8FB1.toInt()),
        PLAYBOOK("PLAYBOOK", 0xFF2EC4B6.toInt()),
        SIMULATION("SIMULATION", 0xFF6BD6A0.toInt()),
        FUNDING("FUNDING & OI", 0xFFFFA45C.toInt()),
        CHAIN("CHAIN WATCH", 0xFFFF9A5C.toInt()),
        CHART("CHART ANALYSIS", 0xFF5AA9FF.toInt()),
        SETUP("SETUP CHECK", 0xFF8BE28B.toInt()),
        GUIDE("GUIDE", 0xFFBFC5CC.toInt())
    }

    private val byChip = mapOf(
        "Find setups" to Kind.SETUPS, "Outlook" to Kind.OUTLOOK, "Briefing" to Kind.BRIEFING,
        "News & sessions" to Kind.NEWS, "Forecast" to Kind.FORECAST, "Positions" to Kind.POSITIONS,
        "Habits" to Kind.HABITS, "Weekly review" to Kind.REVIEW, "Match playbook" to Kind.PLAYBOOK,
        "Simulate" to Kind.SIMULATION, "My playbook" to Kind.PLAYBOOK, "Improve it" to Kind.PLAYBOOK,
        "Funding" to Kind.FUNDING, "Memecoins & wallets" to Kind.CHAIN
    )

    fun forChip(label: String): Kind? = byChip[label]

    fun forText(text: String): Kind? = when {
        text.trimStart().startsWith("Review my trade", ignoreCase = true) -> Kind.REVIEW
        else -> null
    }
}
