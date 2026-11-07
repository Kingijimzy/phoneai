package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrionWorkspaceMatrixTest {
    @Test fun detectsModesFromRequests() {
        assertEquals(OrionWorkMode.CODING, OrionWorkspaceMatrix.detect("fix the kotlin build, the gradle test suite fails"))
        assertEquals(OrionWorkMode.TRADING, OrionWorkspaceMatrix.detect("check the bybit chart and my position"))
        assertEquals(OrionWorkMode.GENERAL, OrionWorkspaceMatrix.detect("good morning"))
    }

    @Test fun tradingModeNeverAutoTrades() {
        val block = OrionWorkspaceMatrix.promptBlock(OrionWorkMode.TRADING)
        assertTrue(block.contains("never place, modify or cancel a trade without explicit per-trade owner approval"))
    }

    @Test fun modeDoesNotChangeIdentityOrMemory() {
        OrionWorkMode.values().filter { it != OrionWorkMode.GENERAL }.forEach {
            assertTrue(it.name, OrionWorkspaceMatrix.promptBlock(it).contains("memory, identity and safety rules are unchanged"))
        }
        assertEquals("", OrionWorkspaceMatrix.promptBlock(OrionWorkMode.GENERAL))
    }

    @Test fun modePersistsAndFallsBackToGeneral() {
        val c = RuntimeEnvironment.getApplication()
        OrionWorkspaceMatrix.set(c, OrionWorkMode.GAMING)
        assertEquals(OrionWorkMode.GAMING, OrionWorkspaceMatrix.current(c))
        assertEquals(OrionWorkMode.GENERAL, OrionWorkMode.parse("nonsense"))
    }
}
