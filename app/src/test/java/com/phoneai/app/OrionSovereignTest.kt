package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class OrionSovereignTest {
    @Test fun situationSeparatesRealFromTestAndPresentFromAway() {
        assertEquals(OrionSituation.Environment.EMULATOR_OR_TEST, OrionSituation.environment("generic/sdk_gphone", "sdk_gphone64", false))
        assertEquals(OrionSituation.Environment.REAL_DEVICE, OrionSituation.environment("POCO/alioth/alioth", "M2012K11AG", false))
        val away = OrionSituation.describe(OrionSituation.Environment.REAL_DEVICE, screenOn = false, locked = true, idleSeconds = null)
        assertTrue(away.contains("asleep") && away.contains("real phone") && away.contains("data, never orders"))
        assertTrue(OrionSituation.describe(OrionSituation.Environment.EMULATOR_OR_TEST, true, false, 5).contains("test rig"))
        assertTrue(OrionSituation.describe(OrionSituation.Environment.REAL_DEVICE, true, false, 2000).contains("33 minutes"))
        assertTrue(OrionSituation.describe(OrionSituation.Environment.REAL_DEVICE, true, false, 3).contains("here and talking"))
    }

    @Test fun a2aCardAndRequestRoundTrip() {
        assertEquals(listOf("https://x.io/.well-known/agent-card.json", "https://x.io/.well-known/agent.json"), OrionA2A.cardUrls("https://x.io/"))
        val card = JSONObject("""{"name":"Scout","url":"https://x.io/rpc","skills":[{"name":"search"},{"name":"summarise"}]}""")
        val s = OrionA2A.summarize(card)
        assertTrue(s.contains("Scout") && s.contains("https://x.io/rpc") && s.contains("search; summarise"))
        val body = JSONObject(OrionA2A.requestBody("hello", "id1"))
        assertEquals("message/send", body.getString("method"))
        assertEquals("hello", body.getJSONObject("params").getJSONObject("message").getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun a2aAnswerIsExtractedFromEveryShapeAndErrorsAreNamed() {
        assertEquals("done", OrionA2A.answerText("""{"result":{"parts":[{"kind":"text","text":"done"}]}}"""))
        assertEquals("art", OrionA2A.answerText("""{"result":{"artifacts":[{"parts":[{"text":"art"}]}]}}"""))
        assertEquals("msg", OrionA2A.answerText("""{"result":{"status":{"state":"completed","message":{"parts":[{"text":"msg"}]}}}}"""))
        assertTrue(OrionA2A.answerText("""{"error":{"message":"nope"}}""").startsWith("error"))
        assertTrue(OrionA2A.answerText("not json").startsWith("error"))
    }

    @Test fun probeStaysOnOwnNetworks() {
        listOf("192.168.1.5", "10.0.0.2", "127.0.0.1", "100.101.2.3", "169.254.1.1").forEach {
            assertTrue(it, OrionA2A.isOwnNetwork(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "1.1.1.1", "100.128.0.1").forEach { assertFalse(it, OrionA2A.isOwnNetwork(InetAddress.getByName(it))) }
        assertTrue(OrionA2A.run(JSONObject().put("action", "probe").put("host", "8.8.8.8")).contains("own network"))
        assertEquals(listOf(22, 80), OrionA2A.parsePorts("22, 80, 99999, x, 22"))
        assertTrue(OrionA2A.parsePorts("").contains(22))
    }

    @Test fun killedScriptsGetAReplaceNotRetryHint() {
        assertTrue(OrionKilledScript.hint("python_exec", "Killed").contains("replacement"))
        assertTrue(OrionKilledScript.hint("shell", "process exit code 137").isNotEmpty())
        assertEquals("", OrionKilledScript.hint("python_exec", "all good"))
        assertEquals("", OrionKilledScript.hint("recall", "Killed"))
    }

    @Test fun a2aIsARegisteredToolWithAHandler() {
        assertTrue(ToolRegistry.find("a2a") != null)
        assertTrue(ToolRegistry.isImplemented("a2a"))
    }
}
