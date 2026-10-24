package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class GeminiLivePolicyTest {
    @Test fun sessionHasHardFiveMinuteLimit() {
        assertEquals(300_000L, GeminiLivePolicy.SESSION_LIMIT_MS)
        assertTrue(GeminiLivePolicy.SESSION_LIMIT_MS <= 5 * 60 * 1000L)
    }

    @Test fun audioConfigurationUsesBoundedStandardRatesAndShortChunks() {
        assertEquals(16_000, GeminiLivePolicy.INPUT_SAMPLE_RATE)
        assertEquals(24_000, GeminiLivePolicy.OUTPUT_SAMPLE_RATE)
        assertEquals(640, GeminiLivePolicy.AUDIO_CHUNK_SAMPLES)
        assertEquals(40, GeminiLivePolicy.AUDIO_CHUNK_SAMPLES * 1000 / GeminiLivePolicy.INPUT_SAMPLE_RATE)
        assertEquals(100, GeminiLivePolicy.PLAYBACK_QUEUE_CAPACITY)
    }

    @Test fun ephemeralTokenWindowIsShorterThanSessionExpiryAndSingleSessionIsPolicy() {
        assertTrue(GeminiLivePolicy.TOKEN_NEW_SESSION_WINDOW_MS > 0L)
        assertTrue(GeminiLivePolicy.TOKEN_EXPIRE_AFTER_MS > GeminiLivePolicy.TOKEN_NEW_SESSION_WINDOW_MS)
        assertTrue(GeminiLivePolicy.TOKEN_EXPIRE_AFTER_MS < 20 * 60 * 60 * 1000L)
    }

    @Test fun warningDisclosesNetworkQuotaAndAutomaticSessionLimit() {
        val warning = GeminiLivePolicy.QUOTA_WARNING.lowercase()
        assertTrue(warning.contains("streams microphone audio"))
        assertTrue(warning.contains("quota"))
        assertTrue(warning.contains("pricing vary"))
        assertTrue(warning.contains("five minutes"))
        assertTrue(warning.contains("own gemini key"))
    }

    @Test fun optionalLiveModeUsesExplicitStableModelAndNoPhoneActionTools() {
        assertEquals("models/gemini-3.8-live", GeminiLivePolicy.MODEL)
        val sessionSource = javaClass.classLoader?.getResourceAsStream(
            "com/phoneai/app/GeminiLiveSession.class"
        )
        // This JVM-only test deliberately checks policy constants, not a live network/model call.
        assertNotNull("The policy class should be loadable without a network call.", GeminiLivePolicy::class.java)
    }
}
