package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionCharacterTest {
    @Test fun characterIsAWorldviewWithWorkedExamplesAndStaysCompact() {
        val c = OrionPersona.CHARACTER
        assertTrue(c.contains("Your philosophy"))
        assertTrue(c.contains("EXAMPLES OF YOUR VOICE"))
        assertTrue(c.contains("extension_forge"))
        assertTrue("character grew to ${c.length}", c.length < 4_200)
        assertTrue(OrionPersona.CORE.length + c.length + OrionPersona.OPERATING.length < 11_000)
    }

    @Test fun forgedToolsAreDirectlyCallableAndRouteThroughExtensionRun() {
        val def = OrionForgedTools.definition("price_check", "fetch a price")
        assertEquals("ext_price_check", def.name)
        assertTrue(OrionForgedTools.isForged("ext_price_check"))
        assertFalse(OrionForgedTools.isForged("extension_run"))
        val (tool, args) = OrionForgedTools.rewrite("ext_price_check", JSONObject().put("input", JSONObject().put("s", "BTC")))
        assertEquals("extension_run", tool)
        assertEquals("price_check", args.getString("id"))
        assertEquals("BTC", args.getJSONObject("input").getString("s"))
        val (t2, a2) = OrionForgedTools.rewrite("ext_x", JSONObject().put("s", "ETH"))
        assertEquals("extension_run", t2)
        assertEquals("ETH", a2.getJSONObject("input").getString("s"))
        assertEquals("python_exec", OrionForgedTools.rewrite("python_exec", JSONObject()).first)
    }
}
