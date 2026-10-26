package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ToolPackCoverageTest {
    @Test fun everyRegisteredToolBelongsToACapabilityPack() {
        val orphans = ToolRegistry.all.map { it.name }.filter { AgentPolicy.packForTool(it) == null }
        assertTrue("Tools denied as 'unknown pack': $orphans", orphans.isEmpty())
    }

    @Test fun mappedPacksExist() {
        ToolRegistry.all.mapNotNull { AgentPolicy.packForTool(it.name) }.toSet().forEach { id ->
            assertTrue("pack $id missing", AgentPolicy.packs.any { it.id == id })
        }
    }
}
