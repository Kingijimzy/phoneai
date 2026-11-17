package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiNativeVoiceTest {
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)

    @Test fun nativeAudioPartsAreDecodedInOrderAndTextIsIgnored() {
        val content = JSONObject().put("modelTurn", JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/pcm;rate=24000").put("data", b64(byteArrayOf(1, 2, 3, 4)))))
            .put(JSONObject().put("text", "hello"))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/pcm;rate=24000").put("data", b64(byteArrayOf(5, 6)))))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/png").put("data", b64(byteArrayOf(9)))))))
        val parts = GeminiLivePolicy.audioParts(content)
        assertEquals(2, parts.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), parts[0]); assertArrayEquals(byteArrayOf(5, 6), parts[1])
        assertTrue(GeminiLivePolicy.audioParts(JSONObject()).isEmpty())
    }

    @Test fun voiceModelCanCallOrionAndGetTheResultBack() {
        val decl = GeminiLivePolicy.toolDeclarations().getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("ask_orion", decl.getString("name")); assertEquals("NON_BLOCKING", decl.getString("behavior"))
        val call = JSONObject().put("toolCall", JSONObject().put("functionCalls", JSONArray().put(
            JSONObject().put("id", "c1").put("name", "ask_orion").put("args", JSONObject().put("request", "open WhatsApp")))))
        assertEquals(listOf(Triple("c1", "ask_orion", "open WhatsApp")), GeminiLivePolicy.toolRequests(call))
        val reply = GeminiLivePolicy.toolResponse("c1", "ask_orion", "Opened.").getJSONObject("toolResponse")
            .getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("c1", reply.getString("id")); assertEquals("Opened.", reply.getJSONObject("response").getString("result"))
    }

    @Test fun longResultsAreBounded() {
        val r = GeminiLivePolicy.toolResponse("a", "ask_orion", "x".repeat(9000)).getJSONObject("toolResponse")
            .getJSONArray("functionResponses").getJSONObject(0).getJSONObject("response").getString("result")
        assertEquals(2000, r.length)
    }

    @Test fun playerLevelTracksLoudness() {
        assertEquals(0f, GeminiNativePlayer.rms(ByteArray(0)), 0f)
        val quiet = ByteArray(200)
        val loud = ByteArray(200).also { for (i in 0 until 200 step 2) { it[i] = 0x00; it[i + 1] = 0x40 } }
        assertTrue(GeminiNativePlayer.rms(loud) > GeminiNativePlayer.rms(quiet))
    }

    @Test fun styleMakesItAPersonThatActsThroughOrion() {
        val s = GeminiLivePolicy.VOICE_STYLE
        assertTrue(s.contains("ask_orion") && s.contains("humour") && s.contains("memory"))
        assertTrue(GeminiLivePolicy.NATIVE_AUDIO)
        assertTrue(GeminiLivePolicy.SESSION_LIMIT_MS > 10 * 60 * 1000L)
    }
}
