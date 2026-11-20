package com.phoneai.app

/**
 * Repeated-call guardrail (the Hermes pattern): when the model issues the same tool with the same arguments again
 * and again, the result will not change, so tell it so and make it change approach. Warns, never blocks.
 */
internal object OrionToolGuard {
    private const val WARN_AT = 3
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun reset() = seen.clear()

    fun annotate(name: String, args: org.json.JSONObject, result: String): String {
        if (name in setOf("wait_for", "look", "read_screen")) return result // state legitimately changes between calls
        val key = name + "|" + args.toString().hashCode()
        val n = seen.merge(key, 1, Int::plus) ?: 1
        if (n < WARN_AT) return result
        return result + "\n[guard] You have now called $name with these exact arguments $n times. Repeating it will not change the outcome; try a different tool, different arguments, or report what is blocking you."
    }
}
