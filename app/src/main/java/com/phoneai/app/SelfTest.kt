package com.phoneai.app

import android.content.Context

/** One-tap health check: real network calls for live data plus local capability checks. */
object SelfTest {
    data class Result(val name: String, val ok: Boolean, val detail: String)

    private fun check(name: String, block: () -> String?): Result = try {
        val d = block()
        if (d == null) Result(name, false, "no data returned") else Result(name, true, d.replace("\n", " ").take(90))
    } catch (e: Exception) { Result(name, false, (e.message ?: e.javaClass.simpleName).take(90)) }

    /** Blocking; call off the main thread. */
    fun run(c: Context): List<Result> {
        val out = ArrayList<Result>()
        val keyed = Providers.all.filter { Prefs.hasKey(c, it.id) }
        out += Result("AI providers", keyed.isNotEmpty(),
            if (keyed.isEmpty()) "no API key saved" else "${keyed.size} ready: " + keyed.joinToString { it.label }.take(70) +
                if (keyed.size < 2) " (add a 2nd for automatic backup)" else " (rotation available)")
        out += Result("Accessibility (phone control)", PhoneAiService.instance != null,
            if (PhoneAiService.instance != null) "service running" else "enable Orion in Accessibility settings")
        out += check("Weather") { WeatherService.current("Lagos").takeIf { it.isNotBlank() && !it.startsWith("error", true) } }
        out += check("News") { LiveNews.rss("football")?.takeIf { it.isNotBlank() } }
        out += check("Market quote") { MarketQuotes.contextFor("BTC").takeIf { it.isNotBlank() } }
        out += check("Live scores") { LiveSports.answer("Manchester United score")?.takeIf { it.isNotBlank() } }
        val pm = c.packageManager
        fun has(pkg: String) = try { pm.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }
        out += Result("Chrome", has("com.android.chrome"), if (has("com.android.chrome")) "installed" else "not installed")
        out += Result("YouTube / music", has("com.google.android.youtube") || has("com.google.android.apps.youtube.music") || has("com.spotify.music"),
            "YouTube=${has("com.google.android.youtube")} YT Music=${has("com.google.android.apps.youtube.music")} Spotify=${has("com.spotify.music")}")
        out += Result("WhatsApp", has("com.whatsapp") || has("com.whatsapp.w4b"), if (has("com.whatsapp")) "installed" else if (has("com.whatsapp.w4b")) "Business installed" else "not installed")
        val fast = FastIntents.parse("play afrobeats on youtube") is FastIntents.Intent.PlayMedia &&
            FastIntents.parse("open chrome and search arsenal fixtures") is FastIntents.Intent.ChromeSearch
        out += Result("Fast commands (no AI)", fast, if (fast) "music + Chrome parse instantly" else "intent parsing broken")
        val exp = ExperienceStore.all(c)
        out += Result("Learning", true, "${exp.count { it.kind == "failure" }} things to avoid, ${exp.count { it.kind == "worked" }} proven routines remembered")
        return out
    }

    fun format(r: List<Result>): String =
        r.joinToString("\n") { (if (it.ok) "✅ " else "❌ ") + it.name + " — " + it.detail } +
            "\n\n" + r.count { it.ok } + "/" + r.size + " passed"
}
