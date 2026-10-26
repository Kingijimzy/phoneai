package com.phoneai.app

import android.content.Context
import org.json.JSONArray

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
    val noKey: Boolean = false,
    /** Provider/account/model may incur usage charges; disabled unless the user opts in. */
    val requiresPaidOptIn: Boolean = false
) {
    val hasVision: Boolean get() = visionModel != null
}

object Providers {
    val all = listOf(
        Provider(
            "groq", "Groq", "https://api.groq.com/openai/v1",
            "openai/gpt-oss-120b", "qwen/qwen3.8-27b", "gsk_...",
            "FREE. Very fast chat. console.groq.com"
        ),
        Provider(
            "cerebras", "Cerebras", "https://api.cerebras.ai/v1",
            "llama-3.3-70b", null, "csk-...",
            "FREE. Extremely fast, text only. cloud.cerebras.ai"
        ),
        Provider(
            "gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-flash-lite-latest", "gemini-flash-latest", "AIza...",
            "FREE. Best for reading the screen. aistudio.google.com"
        ),
        Provider(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            "nvidia/nemotron-3.5-lightning:free", "nvidia/nemotron-3.5-super-vl:free", "sk-or-...",
            "Free model endpoints; request limits and privacy terms vary. Avoid sending secrets. openrouter.ai"
        ),
        Provider(
            "nvidia", "NVIDIA API Catalog", "https://integrate.api.nvidia.com/v1",
            "nvidia/nemotron-3.5-super-vl-120b-a12b", "nvidia/nemotron-3.5-super-vl-120b-a12b", "nvapi-...",
            "NVIDIA Nemotron 3.5 Super VL: text chat and image/video understanding. Confirm endpoint access in your NVIDIA account. build.nvidia.com",
            requiresPaidOptIn = true
        ),
        Provider(
            "mistral", "Mistral", "https://api.mistral.ai/v1",
            "mistral-small-latest", "mistral-small-latest", "your Mistral key",
            "FREE tier. console.mistral.ai",
            requiresPaidOptIn = true
        ),
        // GitHub Models was retired on 2026-07-30; do not expose a dead endpoint as a free provider.
        Provider(
            "sambanova", "SambaNova", "https://api.sambanova.ai/v1",
            "Meta-Llama-3.3-70B-Instruct", "Llama-3.2-11B-Vision-Instruct", "your SambaNova key",
            "FREE tier. cloud.sambanova.ai",
            requiresPaidOptIn = true
        ),
        Provider(
            "together", "Together AI", "https://api.together.xyz/v1",
            "meta-llama/Llama-3.3-70B-Instruct-Turbo", "meta-llama/Llama-3.2-11B-Vision-Instruct-Turbo", "your Together key",
            "Free credits. together.ai",
            requiresPaidOptIn = true
        ),
        Provider(
            "fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1",
            "accounts/fireworks/models/llama-v3p3-70b-instruct", "accounts/fireworks/models/llama-v3p2-11b-vision-instruct", "fw_...",
            "Free credits. fireworks.ai",
            requiresPaidOptIn = true
        ),
        Provider(
            "huggingface", "Hugging Face", "https://router.huggingface.co/v1",
            "meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen2.5-VL-72B-Instruct", "hf_...",
            "Very small free inference credit (currently $0.10/month; subject to change). huggingface.co/settings/tokens",
            requiresPaidOptIn = true
        ),
        Provider(
            "deepseek", "DeepSeek", "https://api.deepseek.com",
            "deepseek-chat", null, "sk-...",
            "Very cheap (paid), text only. platform.deepseek.com",
            requiresPaidOptIn = true
        ),
        Provider(
            "openai", "OpenAI", "https://api.openai.com/v1",
            "gpt-4.1-mini", "gpt-4.1-mini", "sk-...",
            "Paid. platform.openai.com",
            requiresPaidOptIn = true
        ),
        Provider(
            "anthropic", "Anthropic Claude", "https://api.anthropic.com/v1",
            "claude-haiku-5-5", "claude-sonnet-5-5", "sk-ant-...",
            "Paid. console.anthropic.com",
            null, true, requiresPaidOptIn = true
        ),
        Provider(
            "opencode-zen", "OpenCode Zen", "https://opencode.ai/zen/v1",
            "gpt-6-astra", null, "OpenCode Zen API key",
            "OpenCode Zen account API access. Sign in at opencode.ai/auth and create an API key; usage may be metered.",
            "https://opencode.ai/zen/v1/models", false, false, requiresPaidOptIn = true
        ),
        Provider(
            "local", "Local AI (Ollama in Termux)", "http://127.0.0.1:11434/v1",
            "llama3.2:3b", null, "",
            "FREE and unlimited, runs on this phone inside Termux. Slower, text only.",
            null, false, true
        )
    )

    fun find(id: String?): Provider? = all.firstOrNull { it.id == id }

    fun get(id: String?): Provider = find(id) ?: all[0]
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)

    fun key(c: Context, pid: String): String =
        ApiKeyVault.read(c, "enc_key_$pid", "key_$pid")

    fun setKey(c: Context, pid: String, v: String): Boolean =
        ApiKeyVault.write(c, "enc_key_$pid", "key_$pid", v)

    fun hasKey(c: Context, pid: String): Boolean = key(c, pid).isNotEmpty()

    fun hasAnyKey(c: Context): Boolean = Providers.all.any { hasKey(c, it.id) }

    /** Persist successful model catalogues so the picker can open instantly after app restarts. */
    fun cachedModels(c: Context, pid: String): List<String> {
        val raw = sp(c).getString("model_catalog_$pid", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.distinct()
        } catch (_: Exception) { emptyList() }
    }

    fun saveCachedModels(c: Context, pid: String, models: List<String>) {
        try {
            val a = JSONArray()
            models.filter { it.isNotBlank() }.distinct().take(500).forEach { a.put(it) }
            sp(c).edit().putString("model_catalog_$pid", a.toString())
                .putLong("model_catalog_${pid}_at", System.currentTimeMillis()).apply()
        } catch (_: Exception) { }
    }

    fun cachedModelsAgeMs(c: Context, pid: String): Long {
        val at = sp(c).getLong("model_catalog_${pid}_at", 0L)
        return if (at <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    /** One-time PKCE verifier for the OpenRouter browser authorization callback. */
    fun setOpenRouterOAuthVerifier(c: Context, value: String): Boolean =
        ApiKeyVault.write(c, "enc_openrouter_oauth_verifier", "openrouter_oauth_verifier", value)

    fun openRouterOAuthVerifier(c: Context): String =
        ApiKeyVault.read(c, "enc_openrouter_oauth_verifier", "openrouter_oauth_verifier")

    /** Selected external coding-agent CLI runtime. Selection does not imply installation or authentication. */
    fun cliRuntime(c: Context): String = sp(c).getString("cli_runtime", "opencode")?.takeIf {
        it in setOf("muse-code", "cursor-agent", "opencode")
    } ?: "opencode"
    fun setCliRuntime(c: Context, id: String) {
        val safe = id.takeIf { it in setOf("muse-code", "cursor-agent", "opencode") } ?: "opencode"
        sp(c).edit().putString("cli_runtime", safe).apply()
    }

    /** Live Gemini mode is opt-in; the existing local live-call path remains the default. */
    val GEMINI_VOICES = listOf("Aoede", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Zephyr")
    fun geminiVoice(c: Context): String = sp(c).getString("gemini_voice", "Aoede")?.takeIf { it in GEMINI_VOICES } ?: "Aoede"
    fun setGeminiVoice(c: Context, v: String) = sp(c).edit().putString("gemini_voice", if (v in GEMINI_VOICES) v else "Aoede").apply()
    /** low | medium | high: how readily Gemini Live decides that you started or finished speaking. */
    fun geminiMicSensitivity(c: Context): String = sp(c).getString("gemini_mic_sensitivity", "medium")?.takeIf { it in setOf("low", "medium", "high") } ?: "medium"
    fun setGeminiMicSensitivity(c: Context, v: String) = sp(c).edit().putString("gemini_mic_sensitivity", if (v in setOf("low", "medium", "high")) v else "medium").apply()

    fun geminiLiveEnabled(c: Context): Boolean = sp(c).getBoolean("gemini_live_enabled", false)
    fun setGeminiLiveEnabled(c: Context, enabled: Boolean) {
        sp(c).edit().putBoolean("gemini_live_enabled", enabled).apply()
    }

    // ---- trading (Orion Trading)
    // ---- trading
    /** Analysis-first trading is enabled by default; order placement needs an explicit opt-in. */
    fun tradingReadOnly(c: Context): Boolean = sp(c).getBoolean("trading_read_only", true)
    fun setTradingReadOnly(c: Context, enabled: Boolean) = sp(c).edit().putBoolean("trading_read_only", enabled).apply()

    /** Active task preset changes planning emphasis, never the underlying safety gates. */
    fun taskPreset(c: Context): String = sp(c).getString("task_preset", "GENERAL")?.uppercase()?.takeIf { it in setOf("GENERAL", "TRADING", "GAMING", "RESEARCH") } ?: "GENERAL"
    fun setTaskPreset(c: Context, preset: String) = sp(c).edit().putString("task_preset", preset.uppercase().takeIf { it in setOf("GENERAL", "TRADING", "GAMING", "RESEARCH") } ?: "GENERAL").apply()

    /** PLAN blocks write tools; ACT uses normal scoped controls; FULL still keeps safety and confirmation gates. */
    fun executionMode(c: Context): String = sp(c).getString("execution_mode", "ACT")?.uppercase()?.takeIf { it in setOf("PLAN", "ACT", "FULL") } ?: "ACT"
    fun setExecutionMode(c: Context, mode: String) = sp(c).edit().putString("execution_mode", mode.uppercase().takeIf { it in setOf("PLAN", "ACT", "FULL") } ?: "ACT").apply()
    fun bybitKey(c: Context): String =
        ApiKeyVault.read(c, "enc_bybit_key", "bybit_key")
    fun bybitSecret(c: Context): String =
        ApiKeyVault.read(c, "enc_bybit_secret", "bybit_secret")
    fun setBybit(c: Context, k: String, s: String): Boolean {
        val keySaved = ApiKeyVault.write(c, "enc_bybit_key", "bybit_key", k)
        val secretSaved = ApiKeyVault.write(c, "enc_bybit_secret", "bybit_secret", s)
        return keySaved && secretSaved
    }

    /** Practice money by default. Real money must be switched on on purpose. */
    fun bybitTestnet(c: Context): Boolean = sp(c).getBoolean("bybit_test", true)
    fun setBybitTestnet(c: Context, v: Boolean) = sp(c).edit().putBoolean("bybit_test", v).apply()

    fun maxOrderUsd(c: Context): Float = sp(c).getFloat("max_order", 100f)
    fun setMaxOrderUsd(c: Context, v: Float) = sp(c).edit().putFloat("max_order", v).apply()

    fun maxRiskPct(c: Context): Float = sp(c).getFloat("max_risk", 2f)
    fun setMaxRiskPct(c: Context, v: Float) = sp(c).edit().putFloat("max_risk", v).apply()

    fun watchlist(c: Context): String = sp(c).getString("watchlist", "BTCUSDT,ETHUSDT,SOLUSDT,XRPUSDT,BNBUSDT") ?: ""
    fun setWatchlist(c: Context, v: String) = sp(c).edit().putString("watchlist", v).apply()

    fun watchOn(c: Context): Boolean = sp(c).getBoolean("watch_on", false)
    fun setWatchOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("watch_on", v).apply()

    fun watchMinutes(c: Context): Int = sp(c).getInt("watch_min", 10)
    fun setWatchMinutes(c: Context, v: Int) = sp(c).edit().putInt("watch_min", v.coerceIn(5, 120)).apply()

    /** JSON list of price alerts: [{"symbol":"BTCUSDT","dir":"above","price":70000}] */
    fun alerts(c: Context): String = sp(c).getString("alerts", "[]") ?: "[]"
    fun setAlerts(c: Context, v: String) = sp(c).edit().putString("alerts", v).apply()

    /** Optional command/agent provider override. Empty means inherit the normal chat provider. */
    fun commandProviderId(c: Context): String? = sp(c).getString("command_provider_id", null)
        ?.takeIf { it.isNotBlank() && Providers.find(it) != null }

    fun commandModelId(c: Context): String? = sp(c).getString("command_model_id", null)
        ?.takeIf { it.isNotBlank() }

    /** Prefer configured fast/free command providers; otherwise inherit the user's chat provider. */
    fun defaultCommandProvider(c: Context): Provider {
        val fast = listOf("groq", "cerebras").mapNotNull { Providers.find(it) }
            .firstOrNull { hasKey(c, it.id) && (!it.requiresPaidOptIn || allowPaidProviders(c)) }
        return fast ?: provider(c, false)
    }

    fun setCommandProvider(c: Context, providerId: String, modelId: String) {
        val provider = Providers.find(providerId) ?: return
        val model = modelId.trim().take(180).ifBlank { provider.textModel }
        sp(c).edit().putString("command_provider_id", provider.id)
            .putString("command_model_id", model).apply()
    }

    fun clearCommandProvider(c: Context) {
        sp(c).edit().remove("command_provider_id").remove("command_model_id").apply()
    }

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
        val saved = sp(c).getString((if (vision) "vm_" else "tm_") + p.id, null)
        // Migrate stale NVIDIA preferences. Model pickers can persist embedding,
        // retrieval, or vision-only catalog entries as chat models; those cannot answer chat.
        // Always restore the intended DeepSeek chat default for those incompatible selections.
        // Gemini Live preview models are designed for the real-time Live API, not
        // ordinary OpenAI-compatible chat/completions image requests. Migrate them to
        // the provider's supported standard image-understanding model.
        if (vision && p.id == "gemini" && saved != null && saved.lowercase().contains("live")) {
            sp(c).edit().putString("vm_${p.id}", def).apply()
            return def
        }
        // Groq retired its old Llama 4 vision endpoints. Migrate saved models to its
        // current Qwen multimodal endpoint instead of repeatedly receiving HTTP 404.
        if (vision && p.id == "groq" && saved != null &&
            (saved.lowercase().contains("llama-4") || saved.lowercase().contains("llama-3.2") || saved.lowercase().contains("qwen3.6"))) {
            sp(c).edit().putString("vm_${p.id}", def).apply()
            return def
        }
        if (p.id == "nvidia" && saved != null) {
            val id = saved.lowercase()
            val retired = saved in setOf(
                "meta/llama-3.3-70b-instruct",
                "nvidia/nemotron-3-nano-30b-a3b",
                "openai/gpt-oss-20b",
                "nvidia/llama-3.1-nemotron-70b-instruct"
            )
            val incompatibleChat = !vision && listOf(
                "embed", "retriever", "rerank", "vlm", "vision", "-vl", "llava", "paligemma"
            ).any { id.contains(it) }
            if (retired || incompatibleChat) {
                sp(c).edit().putString((if (vision) "vm_" else "tm_") + p.id, def).apply()
                return def
            }
        }
        return saved ?: def
    }

    fun setModel(c: Context, p: Provider, vision: Boolean, m: String) =
        sp(c).edit().putString((if (vision) "vm_" else "tm_") + p.id, m).apply()

    fun autoSwitch(c: Context): Boolean = sp(c).getBoolean("autoswitch", true)
    fun setAutoSwitch(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoswitch", v).apply()

    /** Defaults on to avoid burning free-tier quota through repeated provider/model fallbacks. */
    fun quotaSaver(c: Context): Boolean = sp(c).getBoolean("quota_saver", true)
    fun setQuotaSaver(c: Context, v: Boolean) = sp(c).edit().putBoolean("quota_saver", v).apply()

    /** Paid or metered provider/model requests are blocked unless the user explicitly opts in. */
    fun allowPaidProviders(c: Context): Boolean = sp(c).getBoolean("allow_paid_providers", false)
    fun setAllowPaidProviders(c: Context, v: Boolean) = sp(c).edit().putBoolean("allow_paid_providers", v).apply()

    /** Whether Orion may save new successful tool routes for future planning. */
    fun routeLearning(c: Context): Boolean = sp(c).getBoolean("route_learning", true)
    fun setRouteLearning(c: Context, enabled: Boolean) = sp(c).edit().putBoolean("route_learning", enabled).apply()

    val knownLanguageLabels: Map<String, String> = linkedMapOf(
        "en" to "English",
        "yo" to "Yorùbá",
        "ha" to "Hausa",
        "ig" to "Igbo",
        "fr" to "French",
        "es" to "Spanish",
        "pt" to "Portuguese",
        "ar" to "Arabic"
    )

    fun knownLanguages(c: Context): Set<String> =
        (sp(c).getStringSet("known_languages", setOf("en")) ?: setOf("en")).toSet()
            .filter { it in knownLanguageLabels.keys }.toSet().ifEmpty { setOf("en") }

    fun setKnownLanguages(c: Context, languages: Set<String>) {
        val safe = languages.filter { it in knownLanguageLabels.keys }.toSet().ifEmpty { setOf("en") }
        sp(c).edit().putStringSet("known_languages", safe).apply()
    }

    fun languagePolicy(c: Context): String {
        val labels = knownLanguages(c).mapNotNull { knownLanguageLabels[it] }
        return "Speak and write only in these user-approved languages: " + labels.joinToString(", ") +
            ". Do not switch to another language unless the user changes this setting."
    }

    /** Only the two user-approved live-call visuals are valid; old orb/wave values migrate to dots. */
    fun liveCallStyle(c: Context): String = LiveCallStylePolicy.normalize(sp(c).getString("live_call_style", "dot_matrix"))
    fun setLiveCallStyle(c: Context, v: String) = sp(c).edit()
        .putString("live_call_style", LiveCallStylePolicy.normalize(v)).apply()

    fun voice(c: Context): Boolean = sp(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("voice", v).apply()

    fun hands(c: Context): Boolean = sp(c).getBoolean("hands", true)
    fun setHands(c: Context, v: Boolean) = sp(c).edit().putBoolean("hands", v).apply()

    fun wake(c: Context): Boolean = sp(c).getBoolean("wake", false)
    fun setWake(c: Context, v: Boolean) = sp(c).edit().putBoolean("wake", v).apply()

    /** 0 is strict/less sensitive; 100 is more sensitive. */
    fun wakeSensitivity(c: Context): Int = sp(c).getInt("wake_sensitivity", 55).coerceIn(0, 100)
    fun setWakeSensitivity(c: Context, value: Int) = sp(c).edit().putInt("wake_sensitivity", value.coerceIn(0, 100)).apply()
    fun wakeThreshold(c: Context): Float = WakeWordPolicy.thresholdForSensitivity(wakeSensitivity(c))

    fun wakeTestMode(c: Context): Boolean = sp(c).getBoolean("wake_test_mode", false)
    fun setWakeTestMode(c: Context, enabled: Boolean) = sp(c).edit().putBoolean("wake_test_mode", enabled).apply()

    /** When enabled, speech input must use Android on-device recognition or fail closed. */
    fun offlineSpeechOnly(c: Context): Boolean = sp(c).getBoolean("offline_speech_only", true)
    fun setOfflineSpeechOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean("offline_speech_only", v).apply()

    /** Prevent text-to-speech from using a voice that requires a network service. */
    fun offlineVoiceOnly(c: Context): Boolean = sp(c).getBoolean("offline_voice_only", true)
    fun setOfflineVoiceOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean("offline_voice_only", v).apply()

    /** Use the installed English Piper neural voice instead of Android TTS. */
    fun neuralVoice(c: Context): Boolean = sp(c).getBoolean("neural_voice", false)
    fun setNeuralVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("neural_voice", v).apply()

    fun deepVoice(c: Context): Boolean = sp(c).getBoolean("deep", false)
    fun setDeepVoice(c: Context, v: Boolean) = sp(c).edit().putBoolean("deep", v).apply()

    fun rate(c: Context): Float = sp(c).getFloat("rate", 0.98f)
    fun setRate(c: Context, v: Float) = sp(c).edit().putFloat("rate", v).apply()

    fun userName(c: Context): String = sp(c).getString("username", "") ?: ""
    fun setUserName(c: Context, v: String) = sp(c).edit().putString("username", v.trim()).apply()

    fun autoMemory(c: Context): Boolean = sp(c).getBoolean("automemory", true)
    fun setAutoMemory(c: Context, v: Boolean) = sp(c).edit().putBoolean("automemory", v).apply()

    /** A separate in-app disclosure is shown before the AccessibilityService can be enabled. */
    fun accessibilityDisclosureSeen(c: Context): Boolean = sp(c).getBoolean("accessibility_disclosure_seen", false)
    fun setAccessibilityDisclosureSeen(c: Context, v: Boolean) = sp(c).edit().putBoolean("accessibility_disclosure_seen", v).apply()
    fun accessibilityDisclosureAccepted(c: Context): Boolean = sp(c).getBoolean("accessibility_disclosure_accepted", false)
    fun setAccessibilityDisclosureAccepted(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("accessibility_disclosure_accepted", v)
            .putBoolean("accessibility_disclosure_seen", true).apply()
    }

    fun voiceName(c: Context): String = sp(c).getString("voicename", "") ?: ""
    fun setVoiceName(c: Context, v: String) = sp(c).edit().putString("voicename", v).apply()

    /** 0 = Orion (robot), 1 = Nova (friendly face) */
    fun avatar(c: Context): Int = sp(c).getInt("avatar", 0)
    fun setAvatar(c: Context, v: Int) = sp(c).edit().putInt("avatar", v.coerceIn(0, 5)).apply()

    /** Persistent conversational character, independent of the avatar drawing. */
    fun characterProfile(c: Context): String = CharacterProfile.get(sp(c).getString("character_profile", "orion")).id
    fun setCharacterProfile(c: Context, id: String) {
        val safe = CharacterProfile.get(id).id
        sp(c).edit().putString("character_profile", safe).apply()
    }
}
