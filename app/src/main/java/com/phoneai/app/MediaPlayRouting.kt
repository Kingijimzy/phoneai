package com.phoneai.app

import android.content.Intent
import android.net.Uri

/**
 * Native-first media routing shared by Tools.playMedia.
 * Open-only requests never fall through to web YouTube.
 */
internal object MediaPlayRouting {
    fun play(
        svc: PhoneAiService,
        query: String,
        app: String?,
        installed: (String) -> Boolean,
        launch: (Intent, Long) -> String
    ): String {
        val openOnly = query == FastIntents.OPEN_APP_ONLY || query.isBlank()
        val wanted = when (app) {
            "spotify" -> "com.spotify.music"
            "youtube music" -> "com.google.android.apps.youtube.music"
            "youtube" -> if (openOnly) "com.google.android.youtube" else null
            else -> listOf(
                "com.google.android.apps.youtube.music",
                "com.spotify.music",
                "com.apple.android.music",
                "com.amazon.mp3"
            ).firstOrNull { installed(it) }
        }
        fun labelOf(pkg: String): String = when {
            pkg.contains("spotify") -> "Spotify"
            pkg.contains("youtube.music") -> "YouTube Music"
            pkg.contains("youtube") -> "YouTube"
            pkg.contains("amazon") -> "Amazon Music"
            else -> "Music"
        }
        if (wanted != null && installed(wanted)) {
            if (openOnly) {
                val launchIntent = svc.packageManager.getLaunchIntentForPackage(wanted)
                if (launchIntent != null) {
                    val r = launch(launchIntent, 300)
                    if (r == "ok") return "opened " + labelOf(wanted) + " app"
                }
            } else {
                val i = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(android.app.SearchManager.QUERY, query)
                    .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .setPackage(wanted)
                val r = launch(i, 300)
                if (r == "ok") return "playing $query in " + labelOf(wanted)
                val launchIntent = svc.packageManager.getLaunchIntentForPackage(wanted)
                if (launchIntent != null) {
                    val r2 = launch(launchIntent, 300)
                    if (r2 == "ok") return "opened " + labelOf(wanted) + " (play-from-search unavailable)"
                }
            }
        }
        if (openOnly) {
            return "error: no installed music app found (YouTube Music / Spotify). Install one or name the app."
        }
        val v = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        if (installed("com.google.android.youtube")) v.setPackage("com.google.android.youtube")
        val r = launch(v, 300)
        return if (r == "ok") "opened YouTube results for $query" else r
    }
}
