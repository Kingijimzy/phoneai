package com.phoneai.app

/** Only the two user-approved live-call visuals are valid; old saved orb/wave choices migrate to dots. */
internal object LiveCallStylePolicy {
    fun normalize(value: String?): String = when (value?.lowercase()) { "eyes" -> "eyes"; "dot_matrix" -> "dot_matrix"; else -> "orb" }
}
