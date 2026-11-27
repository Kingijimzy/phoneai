package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionClaimCheckTest {
    @Test fun unbackedActionClaimIsFlagged() {
        val claim = OrionClaimCheck.unbacked("I've pushed the file to the repo.", emptyList())
        assertEquals("pushed the file to the repo", claim)
    }

    @Test fun backedActionClaimIsNotFlagged() {
        assertNull(OrionClaimCheck.unbacked("I've pushed the file to the repo.", listOf("shell")))
    }

    @Test fun fabricatedBatteryFigureWithNoCheckIsFlagged() {
        val claim = OrionClaimCheck.unbacked("Ten percent battery, by the way.", emptyList())
        assertEquals("the battery is at 10%", claim)
    }

    @Test fun batteryFigureBackedByBatteryToolIsNotFlagged() {
        assertNull(OrionClaimCheck.unbacked("Battery is at 10%, by the way.", listOf("battery")))
    }

    @Test fun batteryFigureBackedByDeviceInfoToolIsNotFlagged() {
        assertNull(OrionClaimCheck.unbacked("Battery's at 85%.", listOf("device_info")))
    }

    @Test fun annotateMarksAFabricatedBatteryFigureDistinctlyFromAnActionClaim() {
        val annotated = OrionClaimCheck.annotate("Battery is at 10% right now.", emptyList())
        assertTrue(annotated.contains("made up"))
    }

    @Test fun promptForAFigureClaimAsksForAToolNotAnAction() {
        val prompt = OrionClaimCheck.prompt("the battery is at 10%")
        assertTrue(prompt.contains("Call the right tool"))
    }

    @Test fun compoundWordNumberBatteryFigureIsParsedCorrectly() {
        val claim = OrionClaimCheck.unbacked("Looks like battery is at twenty five percent.", emptyList())
        assertEquals("the battery is at 25%", claim)
    }

    @Test fun ordinaryTextWithNoClaimsIsUntouched() {
        assertNull(OrionClaimCheck.unbacked("Here's what I found in the logs.", emptyList()))
    }
}
