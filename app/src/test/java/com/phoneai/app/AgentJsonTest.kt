package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJsonTest {
    @Test fun validObjectParses() {
        val p = AgentJson.parse("""Sure {"say":"Opening","tool":"open_app","args":{"name":"WhatsApp"}}""")
        assertFalse(p.damaged)
        assertEquals("open_app", p.obj.getString("tool"))
    }

    @Test fun truncatedObjectIsDamagedNotShown() {
        val p = AgentJson.parse("""{"say":"Opening WhatsApp to find kingi jimzy and send a hi message.","tool":"open_app""")
        assertTrue(p.damaged)
        assertEquals("Opening WhatsApp to find kingi jimzy and send a hi message.", AgentJson.sayFromDamaged("""{"say":"Opening WhatsApp to find kingi jimzy and send a hi message.","tool":"open_app"""))
    }

    @Test fun plainTextIsASay() {
        val p = AgentJson.parse("All done.")
        assertFalse(p.damaged)
        assertEquals("All done.", p.obj.getString("say"))
    }
}
