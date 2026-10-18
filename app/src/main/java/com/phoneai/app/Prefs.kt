package com.phoneai.app

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)

    fun key(c: Context): String = sp(c).getString("key", "") ?: ""
    fun setKey(c: Context, v: String) = sp(c).edit().putString("key", v.trim()).apply()

    fun voice(c: Context): Boolean = sp(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("voice", v).apply()

    fun hands(c: Context): Boolean = sp(c).getBoolean("hands", false)
    fun setHands(c: Context, v: Boolean) = sp(c).edit().putBoolean("hands", v).apply()

    const val DEFAULT_MODEL = "nvidia/nemotron-3.5-super-vl-120b-a12b"

    fun textModel(c: Context): String =
        sp(c).getString("textModel", DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setTextModel(c: Context, v: String) = sp(c).edit().putString("textModel", v).apply()

    fun visionModel(c: Context): String =
        sp(c).getString("visionModel", DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setVisionModel(c: Context, v: String) = sp(c).edit().putString("visionModel", v).apply()
}
