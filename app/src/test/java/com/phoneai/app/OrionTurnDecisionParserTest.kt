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
}
