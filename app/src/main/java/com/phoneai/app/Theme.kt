package com.phoneai.app

/** Canonical achromatic Orion visual system. Legacy CYAN remains a silver alias during migration. */
object Theme {
    val BG = 0xFF080808.toInt()
    val PANEL = 0xFF141414.toInt()
    val PANEL2 = 0xFF202020.toInt()
    val PANEL3 = 0xFF292929.toInt()
    val LINE = 0xFF343434.toInt()
    val LINE_STRONG = 0xFF484848.toInt()
    val TEXT = 0xFFF5F5F5.toInt()
    val TEXT_SECONDARY = 0xFFD0D0D0.toInt()
    val MUTED = 0xFF999999.toInt()
    val SILVER = 0xFFBFC5CC.toInt()
    @Deprecated("Use SILVER; kept for existing screens during migration.")
    val CYAN = SILVER
    val VIOLET = 0xFF292D32.toInt()
    val DANGER = 0xFFE98291.toInt()
    val WARN = 0xFFE7BD7B.toInt()
    val OK = SILVER
    val ON_ACCENT = 0xFF080808.toInt()

    const val MOTION_FAST_MS = 140
    const val MOTION_MEDIUM_MS = 220
    const val MOTION_SLOW_MS = 280
    const val MOTION_PRESS_MS = 90
    const val CORNER_SMALL_DP = 10
    const val CORNER_MEDIUM_DP = 14
    const val CORNER_LARGE_DP = 20
}