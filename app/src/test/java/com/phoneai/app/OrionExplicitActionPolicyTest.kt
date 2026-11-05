package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionExplicitActionPolicyTest {
    @Test fun detectsCreateAgentRequestsWithATopic() {
        val req = OrionExplicitActionPolicy.createAgentRequest("create a subagent for skill research")
        assertNotNull(req)
        assertEquals("skill research", OrionExplicitActionPolicy.cleanTopic(req!!.topic))
    }

    @Test fun detectsPastTenseWordingTheOwnerUsed() {
        val req = OrionExplicitActionPolicy.createAgentRequest("created a subagent for skill research")
        assertNotNull(req)
        assertEquals("skill research", OrionExplicitActionPolicy.cleanTopic(req!!.topic))
    }

    @Test fun aPronounTopicIsRecognisedForContextResolution() {
        val req = OrionExplicitActionPolicy.createAgentRequest("create subagent for that bro, what wrong with you")
        assertNotNull(req)
        val topic = OrionExplicitActionPolicy.cleanTopic(req!!.topic)
        assertTrue(topic, OrionExplicitActionPolicy.isPronounTopic(topic))
    }

    @Test fun stripsAboutAndTrailingFillerFromFollowUpTopics() {
        assertEquals("crypto and Forex news", OrionExplicitActionPolicy.cleanTopic("about crypto and Forex news bro"))
    }

    @Test fun ordinaryMessagesAreNotCreateRequests() {
        assertNull(OrionExplicitActionPolicy.createAgentRequest("what was real madrid last match score"))
        assertNull(OrionExplicitActionPolicy.createAgentRequest("search for crypto news"))
    }

    @Test fun generatedIdentityFitsTheCustomAgentContract() {
        val topic = "crypto and Forex news"
        val id = OrionExplicitActionPolicy.slug(topic)
        assertTrue(id.matches(Regex("[a-z][a-z0-9_]{2,19}")))
        assertEquals("Crypto And Forex News", OrionExplicitActionPolicy.displayName(topic))
        assertTrue(OrionExplicitActionPolicy.persona(topic).length in 20..700)
    }
}
