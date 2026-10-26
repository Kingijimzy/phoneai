package com.phoneai.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoutineReplayPolicyTest {
    @Test fun parsesRoutineCommandsWithoutMatchingOrdinaryRunSentences() {
        assertEquals(
            RoutineReplayPolicy.Request(RoutineReplayPolicy.Action.RUN, "FC daily rewards"),
            RoutineReplayPolicy.parseRequest("run FC daily rewards")
        )
        assertEquals(
            RoutineReplayPolicy.Request(RoutineReplayPolicy.Action.RUN, "FC daily rewards"),
            RoutineReplayPolicy.parseRequest("Please run the saved routine \"FC daily rewards\".")
        )
        assertEquals(
            RoutineReplayPolicy.Request(RoutineReplayPolicy.Action.RESUME, "FC daily rewards"),
            RoutineReplayPolicy.parseRequest("resume routine FC daily rewards")
        )
        assertNull(RoutineReplayPolicy.parseRequest("run"))
        assertNull(RoutineReplayPolicy.parseRequest("I want to run through the plan"))
    }

    @Test fun routineSelectionRequiresExactNormalizedName() {
        assertEquals("FC daily rewards",
            RoutineReplayPolicy.exactName(listOf("FC daily rewards", "FC rivals"), "fc DAILY rewards"))
        assertNull(RoutineReplayPolicy.exactName(listOf("FC daily rewards"), "run away"))
        assertNull(RoutineReplayPolicy.exactName(listOf("FC daily rewards"), "FC daily"))
    }

    @Test fun scalesCoordinatesButRejectsInvalidDisplayDimensions() {
        assertEquals(500, RoutineReplayPolicy.scaleCoordinate(250, 500, 1000))
        assertEquals(125, RoutineReplayPolicy.scaleCoordinate(250, 1000, 500))
        assertNull(RoutineReplayPolicy.scaleCoordinate(1, 0, 500))
        assertNull(RoutineReplayPolicy.scaleCoordinate(-1, 500, 1000))
        assertNull(RoutineReplayPolicy.scaleCoordinate(500, 500, 500))
    }

    @Test fun signatureMustContainExactly108ValidChannelValues() {
        val valid = JSONArray()
        repeat(108) { valid.put(it % 256) }
        assertEquals(108, RoutineReplayPolicy.signature(JSONObject().put("sig", valid), "sig")?.size)
        assertNull(RoutineReplayPolicy.signature(JSONObject().put("sig", JSONArray().put(2)), "sig"))
        val invalid = JSONArray()
        repeat(108) { invalid.put(if (it == 10) 300 else 10) }
        assertNull(RoutineReplayPolicy.signature(JSONObject().put("sig", invalid), "sig"))
    }

    @Test fun interruptedReplayOnlyRetriesOrSkipsWhenOneDistinctStateMatches() {
        assertEquals(RoutineReplayPolicy.ResumeDecision.RETRY_IN_FLIGHT,
            RoutineReplayPolicy.resumeDecision(beforeMatches = true, afterMatches = false))
        assertEquals(RoutineReplayPolicy.ResumeDecision.SKIP_IN_FLIGHT,
            RoutineReplayPolicy.resumeDecision(beforeMatches = false, afterMatches = true))
        assertEquals(RoutineReplayPolicy.ResumeDecision.AMBIGUOUS,
            RoutineReplayPolicy.resumeDecision(beforeMatches = true, afterMatches = true))
        assertEquals(RoutineReplayPolicy.ResumeDecision.AMBIGUOUS,
            RoutineReplayPolicy.resumeDecision(beforeMatches = false, afterMatches = false))
    }
}