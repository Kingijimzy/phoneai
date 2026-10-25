package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastIntentsTest {
    @Test fun openYoutubeAndPlayMusic() {
        val r = FastIntents.parse("open YouTube and play me some afrobeats") as FastIntents.Intent.PlayMedia
        assertEquals("afrobeats", r.query); assertEquals("youtube", r.app)
    }

    @Test fun playOnSpotify() {
        val r = FastIntents.parse("play Burna Boy on Spotify") as FastIntents.Intent.PlayMedia
        assertEquals("burna boy", r.query); assertEquals("spotify", r.app)
    }

    @Test fun playMusicWithoutApp() {
        val r = FastIntents.parse("please play me music") as FastIntents.Intent.PlayMedia
        assertEquals("music", r.query); assertNull(r.app)
    }

    @Test fun gamesAreNotMusic() {
        assertNull(FastIntents.parse("play FC Mobile"))
        assertNull(FastIntents.parse("play a game"))
    }

    @Test fun chromeSearchVariants() {
        assertEquals("man united score", (FastIntents.parse("go to chrome and search man united score") as FastIntents.Intent.ChromeSearch).query)
        assertEquals("best laptops", (FastIntents.parse("search best laptops on chrome") as FastIntents.Intent.ChromeSearch).query)
        assertEquals("weather in lagos", (FastIntents.parse("google weather in lagos") as FastIntents.Intent.ChromeSearch).query)
        assertNotNull(FastIntents.parse("open chrome and google arsenal fixtures"))
        assertNull(FastIntents.parse("what is the weather"))
    }
}
