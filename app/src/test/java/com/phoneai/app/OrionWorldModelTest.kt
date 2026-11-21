package com.phoneai.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionWorldModelTest {
    private fun world(network: String = "wifi", pct: Int = 80, charging: Boolean = true, running: Int = 0) = JSONObject()
        .put("network", network).put("battery", JSONObject().put("percent", pct).put("charging", charging))
        .put("agents", JSONObject().put("workers", JSONObject().put("running", running).put("max", 3)))

    @Test fun unreadableFieldsAreListedNotGuessed() {
        val o = OrionWorldState.assemble(linkedMapOf("time" to JSONObject().put("zone", "x"), "battery" to null, "network" to "wifi"))
        assertEquals("wifi", o.getString("network"))
        assertFalse(o.has("battery"))
        assertEquals("battery", o.getJSONArray("unreadable").getString(0))
        assertEquals("night", OrionWorldState.partOfDay(2)); assertEquals("morning", OrionWorldState.partOfDay(8))
    }

    @Test fun foresightBlocksOnlyWhatCannotWork() {
        assertEquals(OrionForesight.Level.BLOCK, OrionForesight.assess("research_web", world(network = "offline")).level)
        assertEquals(OrionForesight.Level.OK, OrionForesight.assess("research_web", world()).level)
        assertEquals(OrionForesight.Level.OK, OrionForesight.assess("read_screen", world(network = "offline")).level)
        assertEquals(OrionForesight.Level.BLOCK, OrionForesight.assess("spawn_worker", world(running = 3)).level)
        assertEquals(OrionForesight.Level.WARN, OrionForesight.assess("dev_shell", world(pct = 9, charging = false)).level)
        assertEquals(OrionForesight.Level.OK, OrionForesight.assess("dev_shell", world(pct = 9, charging = true)).level)
        assertEquals(OrionForesight.Level.WARN, OrionForesight.assess("delete_file", world()).level)
        assertEquals("", OrionForesight.note(OrionForesight.Forecast(OrionForesight.Level.OK, "")))
    }

    @Test fun confidenceIsParsedOffTheReplyAndNeverShown() {
        val r = OrionMetacognition.parse("The capital is Abuja.\n\n[confidence: 62]")
        assertEquals(62, r.confidence); assertEquals("The capital is Abuja.", r.text)
        assertEquals(90, OrionMetacognition.parse("Done.\nconfidence: 90%").confidence)
        assertNull(OrionMetacognition.parse("Hello there").confidence)
        assertNull(OrionMetacognition.parse("x\n[confidence: 250]").confidence)
        assertFalse(OrionMetacognition.parse("a\n[confidence: 70]\nb").text.contains("confidence"))
        assertEquals(40, OrionMetacognition.parse("a [confidence: 10]\n[confidence: 40]").confidence)
    }

    @Test fun verificationOnlyForSubstantiveAnswers() {
        assertFalse(OrionMetacognition.worthVerifying("hey", "Hello! How can I help you today with anything at all?"))
        assertFalse(OrionMetacognition.worthVerifying("What is the tallest building in Lagos right now", "Not sure."))
        assertTrue(OrionMetacognition.worthVerifying("What is the tallest building in Lagos right now", "The tallest building in Lagos is the NECOM House, I believe."))
    }

    @Test fun meterHidesWhenMissingOrStale() {
        assertNull(OrionConfidenceView.label(null, 0L))
        val g = OrionConfidence.Grade(92, "web search", 1_000L)
        assertEquals("92% sure · checked by web search", OrionConfidenceView.label(g, 2_000L))
        assertNull(OrionConfidenceView.label(g, 1_000L + 31 * 60 * 1000L))
    }
    @Test fun inlineConfidenceTagIsStrippedFromVisibleText() {
        val r = OrionMetacognition.parse("Do you mean the pipeline? [confidence: 62]")
        assertEquals(62, r.confidence)
        assertEquals("Do you mean the pipeline?", r.text)
    }
}
