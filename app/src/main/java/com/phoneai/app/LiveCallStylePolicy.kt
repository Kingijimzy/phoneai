package com.phoneai.app

/** The living point-cloud orb is the default; dot matrix and eyes remain optional legacy skins. */
internal object LiveCallStylePolicy {
    fun normalize(value: String?): String = when (value?.lowercase()) { "eyes" -> "eyes"; "dot_matrix" -> "dot_matrix"; else -> "orb" }
}
