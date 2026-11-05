package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionPlatformSelfKnowledgeTest {
    @Test fun everyToolTheMapNamesReallyExists() {
        val missing = OrionPlatformSelfKnowledge.referencedTools.filterNot { it in ToolRegistry.names() }
        assertTrue("unknown tools: $missing", missing.isEmpty())
    }
    @Test fun theMapMentionsEveryReferencedTool() {
        val block = OrionPlatformSelfKnowledge.promptBlock()
        OrionPlatformSelfKnowledge.referencedTools.forEach { assertTrue(it, block.contains(it)) }
        assertTrue(block.contains("never bypassed"))
    }
    @Test fun extensionAutoActivationDoesNotRequireASecondEnableApproval() {
        val block = OrionPlatformSelfKnowledge.promptBlock()
        assertTrue(block.contains("do NOT call extension_enable again"))
        assertTrue(block.contains("at least one meaningful input/expected-output test case"))
        assertTrue(block.contains("Only when auto-activation is OFF"))
    }
    @Test fun identityStatementsAreRememberedAsOwnerConfirmed() {
        val c = MemoryAutoIndexer.extract("My name is Kingi and I live in Lagos")
        assertEquals("fact", c?.category)
        assertTrue(c?.ownerConfirmed == true)
    }
}
