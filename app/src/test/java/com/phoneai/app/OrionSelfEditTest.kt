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

    /** Slashed branch names are used inside URL paths, so they must be validated before use. */
    @Test fun branchNamesAreValidated() {
        assertTrue(OrionSelfEdit.branchOk("orion/self-edit"))
        assertTrue(OrionSelfEdit.branchOk("fix/self-edit-branch-ensure"))
        assertTrue(OrionSelfEdit.branchOk("main"))
        assertFalse(OrionSelfEdit.branchOk(""))
        assertFalse(OrionSelfEdit.branchOk("/leading"))
        assertFalse(OrionSelfEdit.branchOk("trailing/"))
        assertFalse(OrionSelfEdit.branchOk("double//slash"))
        assertFalse(OrionSelfEdit.branchOk("has..dots"))
        assertFalse(OrionSelfEdit.branchOk("ends.lock"))
        assertFalse(OrionSelfEdit.branchOk("bad name with spaces"))
        assertFalse(OrionSelfEdit.branchOk("bad~tilde"))
    }
}
