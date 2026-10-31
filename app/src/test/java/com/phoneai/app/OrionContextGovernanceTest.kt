package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrionContextGovernanceTest {
    @Test fun providerCapabilitiesReserveOutputAndExposeModalities() {
        val gemini = OrionProviderContextPolicy.capabilities("gemini", "gemini-2.5-flash")
        assertTrue(gemini.contextTokens > 100_000)
        assertTrue(gemini.vision)
        assertTrue(gemini.audioInput)
        assertTrue(gemini.videoInput)
        val local = OrionProviderContextPolicy.capabilities("local", "unknown-local-model")
        assertFalse(local.vision)
        assertTrue(OrionProviderContextPolicy.inputBudget(local, 2_000) < local.contextTokens)
    }

    @Test fun messagePackingKeepsSystemAndNewestUserTurn() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "system contract"))
        repeat(30) { i ->
            messages.put(JSONObject().put("role", if (i % 2 == 0) "user" else "assistant")
                .put("content", "turn $i " + "context ".repeat(100)))
        }
        val caps = OrionModelCapabilities(2_048, 512, false, false, false, false)
        val packed = OrionProviderContextPolicy.fitMessages(messages, caps, 256)
        assertTrue(packed.length() < messages.length())
        assertEquals("system", packed.optJSONObject(0)?.optString("role"))
        assertTrue(packed.toString().contains("turn 29"))
    }

    @Test fun mcpGrantOnlyExposesExplicitToolsAndMutationsNeedApproval() {
        val grant = OrionMcpServerGrant("calendar", true, setOf("calendar.read", "calendar.create"), 10L)
        assertEquals(listOf("calendar.read", "calendar.create"),
            OrionMcpPolicy.safeToolSubset(grant, listOf("calendar.read", "calendar.create", "contacts.export", "bad tool")))
        assertFalse(OrionMcpPolicy.requiresOwnerConfirmation("calendar.read", true))
        assertTrue(OrionMcpPolicy.requiresOwnerConfirmation("calendar.create", false))
        assertTrue(OrionMcpPolicy.requiresOwnerConfirmation("wallet.transfer", true))
    }

    @Test fun sensorScopesAreExplicitAndExpiredGrantsAreInactive() {
        val grant = OrionSensorGrant(OrionSensorScope.MOTION, true, 100L, 200L, true)
        assertTrue(grant.active(150L))
        assertFalse(grant.active(201L))
        assertFalse(grant.copy(enabled = false).active(150L))
    }
}
