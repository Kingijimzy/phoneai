package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCallStylePolicyTest {
    @Test fun livingOrbIsTheDefaultAndLegacyStylesMigrateToIt() {
        assertEquals("orb", LiveCallStylePolicy.normalize(null))
        assertEquals("orb", LiveCallStylePolicy.normalize("orb"))
        assertEquals("orb", LiveCallStylePolicy.normalize("wave"))
        assertEquals("dot_matrix", LiveCallStylePolicy.normalize("dot_matrix"))
    }

    @Test fun eyesRemainsTheOnlyAlternative() {
        assertEquals("eyes", LiveCallStylePolicy.normalize("eyes"))
        assertEquals("eyes", LiveCallStylePolicy.normalize("EYES"))
    }
}
