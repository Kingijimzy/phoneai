package com.phoneai.app

/**
 * Routing policy for explicit music playback. A failed app handoff must never turn into a
 * browser/search action: search results are not playback and must not be reported as success.
 */
internal object MediaPlaybackPolicy {
    private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    private const val SPOTIFY = "com.spotify.music"

    fun candidates(app: String?): List<String> = when (app?.trim()?.lowercase()) {
        "youtube music" -> listOf(YOUTUBE_MUSIC)
        "spotify" -> listOf(SPOTIFY)
        "youtube" -> emptyList()
        else -> listOf(YOUTUBE_MUSIC, SPOTIFY)
    }

    fun label(packageName: String): String = when (packageName) {
        YOUTUBE_MUSIC -> "YouTube Music"
        SPOTIFY -> "Spotify"
        else -> packageName
    }

    fun unavailable(app: String?, query: String): String {
        val target = app?.trim()?.takeIf { it.isNotBlank() } ?: "YouTube Music or Spotify"
        return "error: $target is not available for direct playback of '$query'. Install or enable a supported music app. No browser or web-search fallback was opened."
    }
}
