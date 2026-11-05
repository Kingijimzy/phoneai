package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionChatTruthUiTest {
    @Test
    fun parsesContextNote() {
        val p = OrionChatTruthUi.parseNote("[CONTEXT]\npacks=3 tools=40")!!
        assertEquals(OrionChatTruthUi.EventKind.CONTEXT, p.kind)
        assertTrue(p.body.contains("packs"))
        assertFalse(p.isFailure)
    }

    @Test
    fun parsesVerifyReject() {
        val p = OrionChatTruthUi.parseNote("[VERIFY:REJECT]\nplay_media: opened web YouTube")!!
        assertEquals(OrionChatTruthUi.EventKind.VERIFY_REJECT, p.kind)
        assertTrue(p.isFailure)
    }

    @Test
    fun parsesTaskRunning() {
        val p = OrionChatTruthUi.parseNote("[TASK:RUNNING]\nRunning open_app")!!
        assertEquals(OrionChatTruthUi.EventKind.TOOL_RUN, p.kind)
    }

    @Test
    fun plainTextIsNotForcedCard() {
        assertEquals(null, OrionChatTruthUi.parseNote("Hello, how can I help?"))
    }
}
