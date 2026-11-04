package com.phoneai.app

import java.util.Locale

/** Reads perpetual-futures funding and open interest alongside price. Crowding and confirmation hints, never a signal on its own. Pure. */
internal object DerivativesRead {
    class Input(
        val symbol: String, val fundingRate: Double, val openInterest: Double, val openInterest24hAgo: Double?,
        val priceChange24hPct: Double, val longShareOfAccounts: Double?
    )

    class Read(val text: String, val tilt: Double, val extremeFunding: Boolean)

    fun read(i: Input): Read {
        val notes = ArrayList<String>()
        var tilt = 0.0
        val fundingPct = i.fundingRate * 100.0
        val annual = fundingPct * 3 * 365
        var extreme = false
        when {
            fundingPct >= 0.05 -> { notes += "Funding is very high (${fmt(fundingPct)}% per 8h, about ${"%.0f".format(Locale.US, annual)}% a year): longs are crowded and paying a lot to stay in."; tilt -= 0.35; extreme = true }
            fundingPct >= 0.02 -> { notes += "Funding is elevated (${fmt(fundingPct)}% per 8h): more longs than shorts."; tilt -= 0.15 }
            fundingPct <= -0.03 -> { notes += "Funding is deeply negative (${fmt(fundingPct)}% per 8h): shorts are crowded, which can fuel a squeeze."; tilt += 0.3; extreme = true }
            fundingPct <= -0.01 -> { notes += "Funding is slightly negative (${fmt(fundingPct)}% per 8h): shorts lean heavy."; tilt += 0.1 }
            else -> notes += "Funding is near neutral (${fmt(fundingPct)}% per 8h)."
        }
        i.openInterest24hAgo?.takeIf { it > 0 }?.let { old ->
            val chg = (i.openInterest - old) / old * 100.0
            val px = i.priceChange24hPct
            val oiWord = "Open interest ${"%+.1f".format(Locale.US, chg)}% in 24h"
            when {
                chg > 3 && px > 1 -> { notes += "$oiWord with price up ${"%.1f".format(Locale.US, px)}%: new money is backing the rise (trend confirmation)."; tilt += 0.2 }
                chg > 3 && px < -1 -> { notes += "$oiWord with price down ${"%.1f".format(Locale.US, -px)}%: new shorts are pressing the drop."; tilt -= 0.2 }
                chg < -3 && px > 1 -> notes += "$oiWord while price rose: shorts closing out, a rise with less conviction."
                chg < -3 && px < -1 -> { notes += "$oiWord while price fell: longs being flushed; selling pressure may be fading."; tilt += 0.05 }
                else -> notes += "$oiWord: no strong confirmation either way."
            }
        }
        i.longShareOfAccounts?.let { l ->
            if (l >= 0.65) { notes += "${"%.0f".format(Locale.US, l * 100)}% of accounts are long: crowded."; tilt -= 0.1 }
            else if (l <= 0.35) { notes += "Only ${"%.0f".format(Locale.US, l * 100)}% of accounts are long: shorts dominate."; tilt += 0.1 }
        }
        return Read("${i.symbol} derivatives: " + notes.joinToString(" "), tilt.coerceIn(-1.0, 1.0), extreme)
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.3f", v)
}
