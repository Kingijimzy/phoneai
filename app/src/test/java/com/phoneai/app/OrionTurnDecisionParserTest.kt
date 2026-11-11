package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionTurnDecisionParserTest {
    @Test fun parsesNaturalReplyEnvelope() {
        assertEquals(
            OrionTurnDecision.Reply("Hello. How can I help?"),
            OrionTurnDecisionParser.parse("""{"decision":"reply","text":"Hello. How can I help?"}""")
        )
    }

    @Test fun parsesObjectiveExecutionEnvelopeWithoutMagicWords() {
        assertEquals(
            OrionTurnDecision.Execute("Inspect the current screen and open the messaging app"),
            OrionTurnDecisionParser.parse("""{"decision":"execute","objective":"Inspect the current screen and open the messaging app"}""")
        )
    }

    @Test fun parsesClarificationEnvelope() {
        assertEquals(
            OrionTurnDecision.Clarify("Which account do you mean?"),
            OrionTurnDecisionParser.parse("""{"decision":"clarify","question":"Which account do you mean?"}""")
        )
    }

    @Test fun malformedOrIncompleteOutputNeverAuthorizesExecution() {
        assertNull(OrionTurnDecisionParser.parse("execute"))
        assertNull(OrionTurnDecisionParser.parse("""{"decision":"execute","objective":""}"""))
        assertNull(OrionTurnDecisionParser.parse("""{"decision":"unknown","objective":"do it"}"""))
        assertNull(OrionTurnDecisionParser.parse(""))
    }

    @Test fun fencedJsonIsAcceptedButUnstructuredTextIsNotAnExecutionGrant() {
        assertEquals(
            OrionTurnDecision.Execute("Check the weather"),
            OrionTurnDecisionParser.parse("""```json
{"decision":"execute","objective":"Check the weather"}
```""")
        )
        assertNull(OrionTurnDecisionParser.parse("I should probably execute a tool now."))
    }
    @Test fun parsesOnlyEvidenceBackedNonSensitiveTomUpdates() {
        val decision = OrionTurnDecisionParser.parse("""{"decision":"reply","text":"I understand.","tom_updates":[{"dimension":"detail_level","value":"prefers concise answers by default","confidence":0.86,"evidence":"Repeated corrections asking for shorter answers."},{"dimension":"health_status","value":"has a condition","confidence":0.99,"evidence":"sensitive inference"},{"dimension":"initiative","value":"wants persistent follow-through","confidence":0.54,"evidence":"one ambiguous request"}]}""")
        assertTrue(decision is OrionTurnDecision.Reply)
        val updates = (decision as OrionTurnDecision.Reply).tomUpdates
        assertEquals(1, updates.size)
        assertEquals("detail_level", updates.single().dimension)
    }

}
