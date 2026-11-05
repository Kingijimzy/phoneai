package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionMemoryLifecycleTest {
    @Test
    fun toolVerifierRejectsWebMusicForOpenGoal() {
        val v = OrionToolOutcomeVerifier.verify("open the music app", "play_media", "opened YouTube results for music")
        assertFalse(v.accept)
    }

    @Test
    fun toolVerifierAcceptsNativeOpen() {
        val v = OrionToolOutcomeVerifier.verify("open music app", "play_media", "opened Spotify app")
        assertTrue(v.accept)
    }
}
