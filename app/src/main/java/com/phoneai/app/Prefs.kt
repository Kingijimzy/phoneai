package com.phoneai.app

import android.content.Context

/** One AI service. All of them speak the same "OpenAI-style" chat protocol. */
class Provider(
    val id: String,
    val label: String,
    val base: String,
    val textModel: String,
    val visionModel: String,
    val keyHint: String
)

object Providers {
    val all = listOf(
        Provider(
            "nvidia", "NVIDIA", "https://integrate.api.nvidia.com/v1",
            "nvidia/nemotron-3.5-super-vl-120b-a12b", "nvidia/nemotron-3.5-super-vl-120b-a12b", "nvapi-..."
        ),
        Provider(
            "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-flash-latest", "gemini-flash-latest", "AIza..."
        ),
        Provider(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            "llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct", "gsk_..."
        ),
        Provider(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            "meta-llama/llama-3.3-70b-instruct:free", "google/gemma-3-27b-it:free", "sk-or-..."
        )
    )

    fun get(id: String?): Provider = all.firstOrNull { it.id == id } ?: all[0]
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)

    fun key(c: Context, pid: String): String {
        val v = sp(c).getString("key_$pid", "") ?: ""
        if (v.isNotEmpty()) return v
        return if (pid == "nvidia") (sp(c).getString("key", "") ?: "") else ""
    }

    fun setKey(c: Context, pid: String, v: String) = sp(c).edit().putString("key_$pid", v.trim()).apply()

    fun hasKey(c: Context, pid: String): Boolean = key(c, pid).isNotEmpty()

    fun hasAnyKey(c: Context): Boolean = Providers.all.any { hasKey(c, it.id) }

    /** Which service answers chat (vision=false) or reads the screen (vision=true). */
    fun provider(c: Context, vision: Boolean): Provider {
        val stored = sp(c).getString(if (vision) "provider_vision" else "provider_text", null)
        if (stored != null) return Providers.get(stored)
        return Providers.all.firstOrNull { hasKey(c, it.id) } ?: Providers.all[0]
    }

    fun setProvider(c: Context, vision: Boolean, pid: String) =
        sp(c).edit().putString(if (vision) "provider_vision" else "provider_text", pid).apply()

    fun model(c: Context, p: Provider, vision: Boolean): String =
        sp(c).getString((if (vision) "vm_" else "tm_") + p.id, null) ?: (if (vision) p.visionModel else p.textModel)

    fun setModel(c: Context, p: Provider, vision: Boolean, m: String) =
        sp(c).edit().putString((if (vision) "vm_" else "tm_") + p.id, m).apply()

    fun voice(c: Context): Boolean = sp(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("voice", v).apply()

    fun hands(c: Context): Boolean = sp(c).getBoolean("hands", false)
    fun setHands(c: Context, v: Boolean) = sp(c).edit().putBoolean("hands", v).apply()
}
