package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionAgentDelegationTest {
    @Test fun cancelledParentPreventsSpecialistCapabilityDispatch() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-task-cancelled", "Research Android background scheduling"
        )
        var dispatched = false
        val result = AgentRunner.runDelegated(
            org.robolectric.RuntimeEnvironment.getApplication(),
            AgentProfiles.find("research")!!,
            manifest.objective,
            manifest,
            { _, _ -> dispatched = true; "unexpected data" },
            { true }
        )
        assertTrue(result.startsWith("error:"))
        assertTrue(result.contains("paused or stopped"))
        assertFalse(dispatched)
    }

    @Test fun researchSpecialistReceivesOnlyPublicReadCapabilities() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-task-42", "Research recent Android agent frameworks"
        )
        assertEquals(OrionAgentDelegation.Access.READ_ONLY_RESEARCH, manifest.access)
        assertEquals("parent-task-42", manifest.parentTaskId)
        assertTrue(manifest.allowedTools.containsAll(setOf("live_web_search", "live_news", "bybit_public_ticker")))
        assertFalse(manifest.allowedTools.any { it in setOf("telegram_send", "sms", "shell", "python_exec", "paper_trade") })
    }

    @Test fun specialistManifestCarriesCanonicalParentObjectiveNode() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!,
            "checkpoint-42",
            "Research Android agent scheduling",
            parentObjectiveId = "research"
        )
        assertEquals("checkpoint-42", manifest.parentTaskId)
        assertEquals("research", manifest.parentObjectiveId)
        assertTrue(manifest.promptBlock().contains("Parent objective node ID: research"))
    }

    @Test fun invalidParentObjectiveIdIsNotPersistedAsAnAssociation() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, null, "Research scheduling", parentObjectiveId = "../wrong"
        )
        assertNull(manifest.parentObjectiveId)
    }

    @Test fun persistedManifestCanBeRestoredForBackgroundProcessRecreation() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        OrionAgentDelegation.clearForTest(context)
        val original = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!,
            "parent-task-restore",
            "Research durable Android background execution",
            parentObjectiveId = "research"
        )
        assertTrue(OrionAgentDelegation.begin(context, original))

        val restored = OrionAgentDelegation.restore(context, original.id)
        assertNotNull(restored)
        assertEquals(original.id, restored!!.id)
        assertEquals(original.parentTaskId, restored.parentTaskId)
        assertEquals(original.parentObjectiveId, restored.parentObjectiveId)
        assertEquals(original.agentId, restored.agentId)
        assertEquals(original.objective, restored.objective)
        assertEquals(original.access, restored.access)
        assertEquals(original.allowedTools, restored.allowedTools)
        assertEquals(original.memoryScope, restored.memoryScope)
        assertEquals(original.maxModelCalls, restored.maxModelCalls)
        assertEquals(1, restored.maxCapabilityCalls)
    }

    @Test fun restoreRejectsUnknownManifestAndMalformedIds() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        OrionAgentDelegation.clearForTest(context)
        assertNull(OrionAgentDelegation.restore(context, "missing"))
        assertNull(OrionAgentDelegation.restore(context, "x".repeat(81)))
        assertNull(OrionAgentDelegation.restore(context, ""))
    }

    @Test fun restoreRejectsPersistedCapabilityScopeWidening() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        OrionAgentDelegation.clearForTest(context)
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-tamper", "Research a public Android topic"
        )
        assertTrue(OrionAgentDelegation.begin(context, manifest))
        val prefs = context.getSharedPreferences("orion_agent_delegation_ledger", android.content.Context.MODE_PRIVATE)
        val runs = org.json.JSONArray(prefs.getString("runs", "[]"))
        runs.getJSONObject(0).put("allowedTools", org.json.JSONArray().put("telegram_send"))
        prefs.edit().putString("runs", runs.toString()).commit()

        assertNull(OrionAgentDelegation.restore(context, manifest.id))
    }

    @Test fun customSpecialistDefaultsToAnalysisOnlyWithNoToolCapabilities() {
        val custom = AgentProfile("social_helper", "Social Helper", "Plans social content", "Create content plans and drafts.")
        val manifest = OrionAgentDelegation.create(custom, "parent-task-7", "Prepare a content calendar")
        assertEquals(OrionAgentDelegation.Access.ANALYSIS_ONLY, manifest.access)
        assertTrue(manifest.allowedTools.isEmpty())
    }

    @Test fun tradingCoachCannotPlaceOrdersFromItsDelegatedScope() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("coach")!!, "parent-trading-task", "Review the BTC chart and identify possible setups"
        )
        assertEquals(OrionAgentDelegation.Access.TRADING_ANALYSIS, manifest.access)
        assertEquals(setOf("bybit_public_ticker"), manifest.allowedTools)
        assertFalse(manifest.allowedTools.contains("paper_trade"))
        assertFalse(manifest.allowedTools.contains("trading_kill_switch"))
    }

    @Test fun delegatedPromptNamesParentObjectiveAndExplicitlyForbidsExternalActions() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-123", "Compare two research sources"
        )
        val prompt = manifest.promptBlock()
        assertTrue(prompt.contains("parent-123"))
        assertTrue(prompt.contains("Compare two research sources"))
        assertTrue(prompt.contains("Do not claim to execute phone actions"))
        assertTrue(prompt.contains("parent Agent must review"))
    }

    @Test fun failedBrokerEvidenceStopsBeforeSpecialistModelCanInventAResult() {
        assertTrue(AgentRunner.isDelegatedCapabilityFailure("error: live_web_search unavailable"))
        assertTrue(AgentRunner.isDelegatedCapabilityFailure("  ERROR: parent dispatch failed"))
        assertFalse(AgentRunner.isDelegatedCapabilityFailure("No recognised public market symbol was supplied"))
        assertFalse(AgentRunner.isDelegatedCapabilityFailure("source evidence"))
        assertFalse(AgentRunner.isDelegatedCapabilityFailure(null))
    }

    @Test fun delegationPromptAndReceiptIncludeCapabilityBudgetAndMemoryScope() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-88", "Research Android scheduling"
        ).copy(maxCapabilityCalls = 2)
        assertTrue(manifest.promptBlock().contains("Capability-call budget: 2"))
        assertTrue(manifest.promptBlock().contains(manifest.memoryScope))
    }

    @Test fun delegationHasBoundedCallBudgetAndNoParentSecretInManifest() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, null, "Research this API key=super-secret and find public docs"
        )
        assertEquals(1, manifest.maxModelCalls)
        assertFalse(manifest.objective.contains("super-secret"))
    }

    @Test fun brokerDispatchesOnlyManifestAllowedReadOnlyTools() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-55", "Research Android background work"
        )
        var dispatched = 0
        val broker = OrionAgentDelegation.CapabilityBroker(manifest) { tool, args ->
            dispatched++
            assertEquals("live_web_search", tool)
            assertEquals("WorkManager Android", args.optString("query"))
            "source evidence"
        }
        assertEquals("source evidence", broker.execute("live_web_search", org.json.JSONObject().put("query", "WorkManager Android")))
        assertEquals(1, dispatched)
        assertEquals(1, broker.callsUsed())
    }

    @Test fun brokerRejectsUnlistedToolWithoutCallingParentDispatcher() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, null, "Research a topic"
        )
        var dispatched = false
        val broker = OrionAgentDelegation.CapabilityBroker(manifest) { _, _ -> dispatched = true; "should not run" }
        assertTrue(broker.execute("telegram_send", org.json.JSONObject()).startsWith("error:"))
        assertFalse(dispatched)
    }

    @Test fun brokerRejectsWriteToolEvenIfManifestIsTampered() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, null, "Research a topic"
        ).copy(allowedTools = setOf("telegram_send"))
        var dispatched = false
        val broker = OrionAgentDelegation.CapabilityBroker(manifest) { _, _ -> dispatched = true; "should not run" }
        assertTrue(broker.execute("telegram_send", org.json.JSONObject()).contains("not read-only"))
        assertFalse(dispatched)
    }

    @Test fun brokerEnforcesPerDelegationCapabilityBudget() {
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, null, "Research a topic"
        )
        var dispatched = 0
        val broker = OrionAgentDelegation.CapabilityBroker(manifest) { _, _ -> dispatched++; "ok" }
        assertEquals("ok", broker.execute("live_web_search", org.json.JSONObject().put("query", "one")))
        assertTrue(broker.execute("live_news", org.json.JSONObject().put("query", "two")).startsWith("error:"))
        assertEquals(1, dispatched)
    }

    @Test fun restoreRejectsTerminalReceiptsAndOrphanedManifests() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        OrionAgentDelegation.clearForTest(context)
        val completed = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-terminal", "Research a public Android topic"
        )
        assertTrue(OrionAgentDelegation.begin(context, completed))
        assertTrue(OrionAgentDelegation.finish(context, completed, "Research completed"))
        assertNull(OrionAgentDelegation.restore(context, completed.id))

        OrionAgentDelegation.clearForTest(context)
        val orphaned = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, null, "Research a public Android topic"
        )
        assertTrue(OrionAgentDelegation.begin(context, orphaned))
        assertNull(OrionAgentDelegation.restore(context, orphaned.id))
    }

    @Test fun restoreRejectsPersistedCapabilityBudgetWidening() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        OrionAgentDelegation.clearForTest(context)
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-budget-tamper", "Research a public Android topic"
        )
        assertTrue(OrionAgentDelegation.begin(context, manifest))
        val prefs = context.getSharedPreferences("orion_agent_delegation_ledger", android.content.Context.MODE_PRIVATE)
        val runs = org.json.JSONArray(prefs.getString("runs", "[]"))
        runs.getJSONObject(0).put("maxCapabilityCalls", 4)
        prefs.edit().putString("runs", runs.toString()).commit()

        assertNull(OrionAgentDelegation.restore(context, manifest.id))
    }

    @Test fun specialistReceiptCannotBeFinishedTwiceOrResurrected() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        OrionAgentDelegation.clearForTest(context)
        val manifest = OrionAgentDelegation.create(
            AgentProfiles.find("research")!!, "parent-finish-once", "Research a public Android topic"
        )
        assertTrue(OrionAgentDelegation.begin(context, manifest))
        assertTrue(OrionAgentDelegation.finish(context, manifest, "completed"))
        assertFalse(OrionAgentDelegation.finish(context, manifest, "late replacement"))
        assertEquals("completed", OrionAgentDelegation.latest(context, 1).single().optString("status"))
    }

}
