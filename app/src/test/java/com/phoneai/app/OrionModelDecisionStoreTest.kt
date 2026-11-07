package com.phoneai.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionModelDecisionStoreTest {

    private lateinit var context: Context
    private lateinit var store: OrionModelDecisionStore

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OrionModelDecisionStore.resetForTests()
        context.deleteDatabase("orion_model_decisions.db")
        store = OrionModelDecisionStore.get(context)
    }

    @After fun tearDown() {
        OrionModelDecisionStore.resetForTests()
    }

    @Test
    fun recordAndCompleteDecision() {
        val id = store.record(
            tool = "model_chat",
            arguments = "provider=nvidia; model=nvidia/nemotron-3.5-super-vl-120b-a12b",
            reason = "Selected NVIDIA provider for chat turn",
            provider = "nvidia",
            model = "nvidia/nemotron-3.5-super-vl-120b-a12b",
            taskId = "task-1",
            turnId = "turn-1"
        )
        assertTrue(id.startsWith("Recorded decision") || id.contains("-"))

        val ok = store.complete(id, "Hello, how can I help?", "PASS")
        assertTrue(ok)

        val decisions = store.forTask("task-1")
        assertEquals(1, decisions.size)
        assertEquals("model_chat", decisions[0].tool)
        assertEquals("PASS", decisions[0].verification)
        assertEquals("nvidia", decisions[0].provider)
        assertEquals("nvidia/nemotron-3.5-super-vl-120b-a12b", decisions[0].model)
        assertFalse(decisions[0].arguments.contains("sk-")) // key redacted if present
    }

    @Test
    fun completesUnknownIdReturnsFalse() {
        val ok = store.complete("nonexistent-id", "result", "PASS")
        assertFalse(ok)
    }

    @Test
    fun searchFindsDecisionsByToolAndReason() {
        store.record(
            tool = "model_chat",
            arguments = "provider=local; model=llama3.2:3b",
            reason = "Local model used while offline",
            provider = "local",
            model = "llama3.2:3b",
            taskId = "task-a",
            turnId = "turn-a"
        )
        store.record(
            tool = "shell",
            arguments = "ls -la",
            reason = "List workspace files",
            taskId = "task-b",
            turnId = "turn-b"
        )

        val byTool = store.search("model_chat")
        assertEquals(1, byTool.size)

        val byReason = store.search("Local model")
        assertEquals(1, byReason.size)
        assertEquals("local", byReason[0].provider)

        val all = store.search("")
        assertTrue(all.isEmpty()) // empty query returns empty
    }

    @Test
    fun unverifiedCountTracksInFlightDecisions() {
        assertEquals(0, store.unverifiedCount("task-1"))

        store.record(
            tool = "model_chat",
            arguments = "provider=groq",
            reason = "In-flight decision",
            taskId = "task-1"
        )
        assertEquals(1, store.unverifiedCount("task-1"))

        // Complete it - count goes back to 0
        val decisions = store.forTask("task-1")
        assertEquals(1, decisions.size)
        val id = decisions[0].id
        store.complete(id, "result", "PASS")
        assertEquals(0, store.unverifiedCount("task-1"))
    }

    @Test
    fun redactedArgumentsStripSecrets() {
        val id = store.record(
            tool = "model_chat",
            arguments = "api_key=sk-1234567890abcdef; model=test",
            reason = "Test with secret in arguments",
            provider = "nvidia"
        )
        val decisions = store.search("Test with secret")
        assertEquals(1, decisions.size)
        assertFalse(decisions[0].arguments.contains("sk-1234567890abcdef"))
        assertTrue(decisions[0].arguments.contains("[redacted]"))
    }

    @Test
    fun emptyToolOrReasonIsRejected() {
        val result1 = store.record("", "{}", "some reason")
        assertTrue(result1.startsWith("error:"))

        val result2 = store.record("tool", "{}", "")
        assertTrue(result2.startsWith("error:"))
    }
}
