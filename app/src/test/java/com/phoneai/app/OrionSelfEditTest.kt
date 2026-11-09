package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionSelfEditTest {
    @Test fun tradingDeskIsProtected() {
        assertTrue(OrionSelfEdit.protectedPath("app/src/main/java/com/phoneai/app/ChainDesk.kt"))
        assertTrue(OrionSelfEdit.protectedPath("app/src/main/java/com/phoneai/app/TradingDeskScreen.kt"))
        assertFalse(OrionSelfEdit.protectedPath("app/src/main/java/com/phoneai/app/OrionPersona.kt"))
    }
    @Test fun toolsAreRegistered() {
        assertNotNull(ToolRegistry.find("self_edit")); assertNotNull(ToolRegistry.find("web_api"))
    }
    @Test fun repoShapeIsChecked() {
        assertTrue(OrionSelfEdit.repoOk("Kingijimzy/phoneai")); assertFalse(OrionSelfEdit.repoOk("../x"))
    }
}
