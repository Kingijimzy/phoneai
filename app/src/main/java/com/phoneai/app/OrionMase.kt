package com.phoneai.app

import android.content.Context

/**
 * Know, Route, Act, Reflect: the working loop every run follows, backed by Orion's layered memory (facts, episodes,
 * verified routes, lessons). MASE closes it: a run that taught something is turned into a stored lesson so the next
 * run starts smarter. This block is the protocol plus the anticipation DAWN learned for this hour.
 */
internal object OrionMase {
    const val PROTOCOL =
        "WORKING LOOP — KNOW, ROUTE, ACT, REFLECT. KNOW: read what memory already gives you (facts, lessons, routes that worked) before touching a tool. " +
        "ROUTE: pick the strongest route for the job (system op, Linux shell, Python, accessibility) and the cheapest one that worked before; if it fails, move down the hierarchy instead of repeating it. " +
        "ACT: do it and verify with evidence, not belief. REFLECT: when something failed, find the cause and fix it; when something worked and would generalise, store one short reusable lesson with remember. " +
        "For anything long, parallel or independent, spawn_worker and keep going."

    fun block(c: Context): String = listOf(PROTOCOL, OrionIntentCompiler.hint(c)).filter { it.isNotBlank() }.joinToString("\n")
}
