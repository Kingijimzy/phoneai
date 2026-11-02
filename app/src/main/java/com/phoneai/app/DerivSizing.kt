package com.phoneai.app

import kotlin.math.abs

/** Turns "exposure, entry, stop, target" into a Deriv multiplier contract whose loss at the stop equals the planned risk. Pure. */
internal object DerivSizing {
    private val multipliers = listOf(20, 30, 50, 100, 200, 300, 400, 500)

    data class Plan(val stake: Double, val multiplier: Int, val stopLossUsd: Double, val takeProfitUsd: Double?, val riskUsd: Double)

    fun plan(exposureUsd: Double, entry: Double, stop: Double, target: Double?): Plan? {
        if (exposureUsd <= 0 || entry <= 0 || stop <= 0 || entry == stop) return null
        val d = abs(entry - stop) / entry
        val m = multipliers.lastOrNull { it <= 1.0 / d } ?: return null // stop wider than 1/20 of price: even the smallest multiplier would risk more than the stake
        val risk = exposureUsd * d
        val stake = exposureUsd / m
        val tp = target?.let { exposureUsd * abs(it - entry) / entry }
        return Plan(stake, m, risk, tp, risk)
    }
}
