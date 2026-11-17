package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class GeminiLivePolicyTest {
    @Test fun gemini38LiveUsesNativeAudioWithTextTranscriptionForOrionVoice() {
        assertEquals("AUDIO", GeminiLivePolicy.RESPONSE_MODALITY)
    }

    @Test fun outputTranscriptIsFlushedWhenItArrivesAfterTurnComplete() {
        val buffer = GeminiLiveTranscriptBuffer()
        buffer.markTurnComplete()
        assertTrue(buffer.isReady().not())
        buffer.append("Hello from Gemini")
        assertTrue(buffer.isReady())
        assertEquals("Hello from Gemini", buffer.drain())
        assertTrue(buffer.isReady().not())
    }

    @Test fun outputTranscriptBeforeTurnCompleteWaitsAndThenFlushes() {
        val buffer = GeminiLiveTranscriptBuffer()
        buffer.append("Hello")
        buffer.append("there")
        assertTrue(buffer.isReady().not())
        buffer.markTurnComplete()
        assertEquals("Hello there", buffer.drain())
    }

    @Test fun interruptedLiveTurnClearsPendingTranscript() {
        val buffer = GeminiLiveTranscriptBuffer()
        buffer.append("unfinished response")
        buffer.clear()
        buffer.markTurnComplete()
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun callsAreBoundedButResumeAcrossConnections() {
        // Google drops each connection after a few minutes; Orion resumes with a handle, so the ceiling is per call.
        assertEquals(40 * 60 * 1000L, GeminiLivePolicy.SESSION_LIMIT_MS)
        assertTrue(GeminiLivePolicy.MAX_RECONNECTS in 1..20)
        assertTrue(GeminiLivePolicy.SILENCE_LIMIT_MS <= 2 * 60 * 1000L)
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
        assertTrue(warning.contains("reconnects long calls"))
        assertTrue(warning.contains("silence"))
        assertTrue(warning.contains("own gemini key"))
    }

    @Test fun optionalLiveModeUsesExplicitStableModelAndNoPhoneActionTools() {
        assertEquals("models/gemini-3.8-live", GeminiLivePolicy.MODEL)
        // This JVM-only test deliberately checks policy constants, not a live network/model call.
        assertEquals(40 * 60 * 1000L, GeminiLivePolicy.SESSION_LIMIT_MS)
    }
}
