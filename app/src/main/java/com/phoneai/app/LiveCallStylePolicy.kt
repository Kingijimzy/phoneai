package com.phoneai.app

/** Only the two user-approved live-call visuals are valid; old saved orb/wave choices migrate to dots. */
internal object LiveCallStylePolicy {
    fun normalize(value: String?): String = if (value.equals("eyes", ignoreCase = true)) "eyes" else "dot_matrix"
}
