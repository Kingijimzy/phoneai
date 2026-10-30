package com.phoneai.app

/**
 * Pure setup matcher: playbook rules vs a market snapshot.
 * Does not place orders; emits SetupCards for the desk / Watcher.
 */
object TradingMatcher {
    data class MarketSnapshot(
        val instrument: String,
        val assetClass: TradingPlaybook.AssetClass,
        val lastPrice: Double?,
        val changePct: Double? = null,
        val session: String? = null,
        val tags: Set<String> = emptySet()
    )

    data class SetupCard(
        val ruleId: String,
        val ruleName: String,
        val instrument: String,
        val direction: TradingPlaybook.Direction,
        val score: Double,
        val reasons: List<String>,
        val expiresHintMinutes: Int = 60
    )

    fun match(
        rules: List<TradingPlaybook.Rule>,
        snapshot: MarketSnapshot,
        nowSession: String? = snapshot.session
    ): List<SetupCard> {
        val inst = TradingPlaybook.normalizeInstrument(snapshot.instrument)
        return rules
            .filter { it.enabled }
            .filter { TradingPlaybook.normalizeInstrument(it.instrument) == inst }
            .mapNotNull { rule -> score(rule, snapshot, nowSession) }
            .sortedByDescending { it.score }
    }

    private fun score(
        rule: TradingPlaybook.Rule,
        snapshot: MarketSnapshot,
        nowSession: String?
    ): SetupCard? {
        val reasons = ArrayList<String>()
        var score = 0.4

        if (rule.assetClass != snapshot.assetClass && rule.assetClass != TradingPlaybook.AssetClass.OTHER) {
            return null
        }
        reasons += "instrument:${rule.instrument}"

        if (rule.direction != TradingPlaybook.Direction.EITHER) {
            reasons += "direction:${rule.direction.name.lowercase()}"
            score += 0.15
        }

        val sessionCond = rule.conditions.firstOrNull { it.startsWith("session:", ignoreCase = true) }
        if (sessionCond != null) {
            val want = sessionCond.removePrefix("session:").trim().lowercase()
            val now = nowSession?.lowercase()
            if (now != null && (now.contains(want) || want.contains(now))) {
                score += 0.2
                reasons += "session_match:$want"
            } else if (now != null) {
                score -= 0.25
                reasons += "session_mismatch:want=$want got=$now"
            }
        }

        snapshot.changePct?.let { pct ->
            if (rule.direction == TradingPlaybook.Direction.LONG && pct > 0) {
                score += 0.1
                reasons += "momentum_with_long"
            }
            if (rule.direction == TradingPlaybook.Direction.SHORT && pct < 0) {
                score += 0.1
                reasons += "momentum_with_short"
            }
        }

        rule.conditions.filter { it.startsWith("level:") }.forEach { reasons += it }

        if (score < 0.35) return null
        return SetupCard(
            ruleId = rule.id,
            ruleName = rule.name,
            instrument = rule.instrument,
            direction = rule.direction,
            score = score.coerceIn(0.0, 1.0),
            reasons = reasons
        )
    }
}
