package com.phoneai.app

/**
 * Stage 1 of the session runner: notices when a task is going nowhere, so Orion stops with a clear
 * report of what it tried instead of repeating the same tap or failing action until the budget runs out.
 * Pure logic, no Android, unit tested.
 */
internal class SessionStall(private val repeatLimit: Int = 3, private val failureLimit: Int = 4) {
    private data class Attempt(val signature: String, val failed: Boolean)
    private val attempts = ArrayList<Attempt>()

    /** Records one completed action. Returns a stop reason when the task has stalled, otherwise null. */
    fun add(tool: String, args: String, result: String): String? {
        val failed = result.startsWith("error")
        attempts += Attempt(tool + " " + args.take(160), failed)
        if (attempts.size >= repeatLimit && attempts.takeLast(repeatLimit).let { last -> last.all { it.signature == last[0].signature } }) {
            return "repeated the same action $repeatLimit times without progress (${tool})"
        }
        if (attempts.size >= failureLimit && attempts.takeLast(failureLimit).all { it.failed }) {
            return "failed $failureLimit actions in a row"
        }
        return null
    }

    /** A short, honest account of the last few actions, for the stop message. */
    fun summary(): String {
        if (attempts.isEmpty()) return ""
        val lines = attempts.takeLast(5).joinToString("\n") { "- " + it.signature.take(90) + if (it.failed) " → failed" else " → ran" }
        return "Here is what I tried:\n$lines\n"
    }
}
