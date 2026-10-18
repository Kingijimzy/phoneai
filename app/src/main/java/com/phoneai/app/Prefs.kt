package com.phoneai.app

import android.content.Context

/** One AI service. Almost all of them speak the same "OpenAI-style" chat protocol. */
class Provider(
    val id: String,
    val label: String,
    val base: String,
    val textModel: String,
    val visionModel: String?,
    val keyHint: String,
    val info: String,
    val listUrl: String? = null,
    val anthropicStyle: Boolean = false,
    val noKey: Boolean = false
) {
    val hasVision: Boolean get() = visionModel != null
}

object Providers {
    val all = listOf(
        Provider(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            "llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct", "gsk_...",
            "FREE. Very fast chat. console.groq.com"
        ),
        Provider(
            "cerebras", "Cerebras", "https://api.cerebras.ai/v1",
            "llama-3.3-70b", null, "csk-...",
            "FREE. Extremely fast, text only. cloud.cerebras.ai"
        ),
        Provider(
            "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-flash-latest", "gemini-flash-latest", "AIza...",
            "FREE. Best for reading the screen. aistudio.google.com"
        ),
        Provider(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            "meta-llama/llama-3.3-70b-instruct:free", "google/gemma-3-27b-it:free", "sk-or-...",
            "FREE models. Many models, one key. openrouter.ai"
        ),
        Provider(
            "mistral", "Mistral", "https://api.mistral.ai/v1",
            "mistral-small-latest", "mistral-small-latest", "your Mistral key",
            "FREE tier. console.mistral.ai"
        ),
        Provider(
            "github", "GitHub Models", "https://models.github.ai/inference",
            "openai/gpt-4o-mini", "openai/gpt-4o-mini", "github_pat_...",
            "FREE with a GitHub token (models access). github.com/marketplace/models",
            "https://models.github.ai/catalog/models"
        ),
        Provider(
            "sambanova", "SambaNova", "https://api.sambanova.ai/v1",
            "Meta-Llama-3.3-70B-Instruct", "Llama-3.2-11B-Vision-Instruct", "your SambaNova key",
            "FREE tier. cloud.sambanova.ai"
        ),
        Provider(
            "together", "Together AI", "https://api.together.xyz/v1",
            "meta-llama/Llama-3.3-70B-Instruct-Turbo", "meta-llama/Llama-3.2-11B-Vision-Instruct-Turbo", "your Together key",
            "Free credits. together.ai"
        ),
        Provider(
            "fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1",
            "accounts/fireworks/models/llama-v3p3-70b-instruct", "accounts/fireworks/models/llama-v3p2-11b-vision-instruct", "fw_...",
            "Free credits. fireworks.ai"
        ),
        Provider(
            "huggingface", "Hugging Face", "https://router.huggingface.co/v1",
            "meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen2.5-VL-72B-Instruct", "hf_...",
            "Free monthly credits. huggingface.co/settings/tokens"
        ),
        Provider(
            "deepseek", "DeepSeek", "https://api.deepseek.com",
            "deepseek-chat", null, "sk-...",
            "Very cheap (paid), text only. platform.deepseek.com"
        ),
        Provider(
            "openai", "OpenAI", "https://api.openai.com/v1",
            "gpt-4.1-mini", "gpt-4.1-mini", "sk-...",
            "Paid. platform.openai.com"
        ),
        Provider(
            "anthropic", "Anthropic Claude", "https://api.anthropic.com/v1",
            "claude-haiku-5-5", "claude-sonnet-5-5", "sk-ant-...",
            "Paid. console.anthropic.com",
            null, true
        ),
        Provider(
            "local", "Local model (Termux)", "http://127.0.0.1:8080/v1",
            "local", null, "type: local",
            "FREE and unlimited, runs on this phone with llama.cpp in Termux. Type the word local as the key. Slower and less smart."
        )
    )

    fun find(id: String?): Provider? = all.firstOrNull { it.id == id }

    fun get(id: String?): Provider = find(id) ?: all[0]
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)

    fun key(c: Context, pid: String): String {
        return sp(c).getString("key_$pid", "") ?: ""
    }

    fun setKey(c: Context, pid: String, v: String) = sp(c).edit().putString("key_$pid", v.trim()).apply()

    fun hasKey(c: Context, pid: String): Boolean = key(c, pid).isNotEmpty()

    fun hasAnyKey(c: Context): Boolean = Providers.all.any { hasKey(c, it.id) }

    /** Which service answers chat (vision=false) or reads the screen (vision=true). */
    fun provider(c: Context, vision: Boolean): Provider {
        val stored = sp(c).getString(if (vision) "provider_vision" else "provider_text", null)
        val sp0 = Providers.find(stored)
        if (sp0 != null && (!vision || sp0.hasVision)) return sp0
        return Providers.all.firstOrNull { hasKey(c, it.id) && (!vision || it.hasVision) } ?: Providers.all[0]
    }

    fun setProvider(c: Context, vision: Boolean, pid: String) =
        sp(c).edit().putString(if (vision) "provider_vision" else "provider_text", pid).apply()

    fun model(c: Context, p: Provider, vision: Boolean): String {
        val def = if (vision) (p.visionModel ?: p.textModel) else p.textModel
        return sp(c).getString((if (vision) "vm_" else "tm_") + p.id, null) ?: def
    }

    fun setModel(c: Context, p: Provider, vision: Boolean, m: String) =
        sp(c).edit().putString((if (vision) "vm_" else "tm_") + p.id, m).apply()

    fun autoSwitch(c: Context): Boolean = sp(c).getBoolean("autoswitch", true)
    fun setAutoSwitch(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoswitch", v).apply()

    fun voice(c: Context): Boolean = sp(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("voice", v).apply()

    fun hands(c: Context): Boolean = sp(c).getBoolean("hands", true)
    fun setHands(c: Context, v: Boolean) = sp(c).edit().putBoolean("hands", v).apply()

    fun wake(c: Context): Boolean = sp(c).getBoolean("wake", false)
    fun setWake(c: Context, v: Boolean) = sp(c).edit().putBoolean("wake", v).apply()

    fun deepVoice(c: Context): Boolean = sp(c).getBoolean("deep", false)
    fun setDeepVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("deep", v).apply()

    fun rate(c: Context): Float = sp(c).getFloat("rate", 1.05f)
    fun setRate(c: Context, v: Float) = sp(c).edit().putFloat("rate", v).apply()

    fun userName(c: Context): String = sp(c).getString("username", "") ?: ""
    fun setUserName(c: Context, v: String) = sp(c).edit().putString("username", v.trim()).apply()

    fun autoMemory(c: Context): Boolean = sp(c).getBoolean("automemory", true)
    fun setAutoMemory(c: Context, v: Boolean) = sp(c).edit().putBoolean("automemory", v).apply()

    fun voiceName(c: Context): String = sp(c).getString("voicename", "") ?: ""
    fun setVoiceName(c: Context, v: String) = sp(c).edit().putString("voicename", v).apply()

    /** 0 = Orion (robot), 1 = Nova (friendly face) */
    fun avatar(c: Context): Int = sp(c).getInt("avatar", 0)
    fun setAvatar(c: Context, v: Int) = sp(c).edit().putInt("avatar", v).apply()
}
