package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackPolicyTest {
    @Test fun explicitYoutubeMusicNeverFallsBackToAnotherApp() {
        assertEquals(listOf("com.google.android.apps.youtube.music"), MediaPlaybackPolicy.candidates("youtube music"))
    }

    @Test fun explicitSpotifyNeverFallsBackToAnotherApp() {
        assertEquals(listOf("com.spotify.music"), MediaPlaybackPolicy.candidates("spotify"))
    }

    @Test fun unspecifiedPlayerUsesOnlySupportedMusicApps() {
        assertEquals(
            listOf("com.google.android.apps.youtube.music", "com.spotify.music"),
            MediaPlaybackPolicy.candidates(null)
        )
    }

    @Test fun unsupportedOrMissingPlayerProducesHonestErrorWithoutWebFallback() {
        val result = MediaPlaybackPolicy.unavailable("YouTube Music", "Asake")
        assertTrue(result.startsWith("error:"))
        assertTrue(result.contains("No browser or web-search fallback"))
    }
}
