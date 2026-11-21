package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionGeminiVoiceTest {
    @Test fun requestAsksForRawPcmAtTheTrackRate() {
        val body = org.json.JSONObject(OrionGeminiVoice.requestBody("Hello Kingi", "Aoede"))
        val cfg = body.getJSONObject("generationConfig")
        assertEquals("AUDIO", cfg.getJSONArray("responseModalities").getString(0))
        assertEquals("AUDIO_L16", cfg.getJSONObject("responseFormat").getJSONObject("audio").getString("mimeType"))
        assertEquals("Hello Kingi", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
    }
    @Test fun pcmConvertsToSignedFloats() {
        val f = OrionGeminiVoice.toFloats(byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()))
        assertEquals(0.5f, f[0], 0.001f); assertEquals(-0.5f, f[1], 0.001f)
    }
}
