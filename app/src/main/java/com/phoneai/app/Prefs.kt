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

    fun textModel(c: Context): String =
        sp(c).getString("textModel", "meta/llama-3.3-70b-instruct") ?: "meta/llama-3.3-70b-instruct"

    fun setTextModel(c: Context, v: String) = sp(c).edit().putString("textModel", v).apply()

    fun visionModel(c: Context): String =
        sp(c).getString("visionModel", "meta/llama-3.2-90b-vision-instruct") ?: "meta/llama-3.2-90b-vision-instruct"

    fun setVisionModel(c: Context, v: String) = sp(c).edit().putString("visionModel", v).apply()
}
