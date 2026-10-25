package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCallStylePolicyTest {
    @Test fun dotMatrixIsTheDefaultAndLegacyStylesMigrateToIt() {
        assertEquals("dot_matrix", LiveCallStylePolicy.normalize(null))
        assertEquals("dot_matrix", LiveCallStylePolicy.normalize("orb"))
        assertEquals("dot_matrix", LiveCallStylePolicy.normalize("wave"))
        assertEquals("dot_matrix", LiveCallStylePolicy.normalize("dot_matrix"))
    }

    @Test fun eyesRemainsTheOnlyAlternative() {
        assertEquals("eyes", LiveCallStylePolicy.normalize("eyes"))
        assertEquals("eyes", LiveCallStylePolicy.normalize("EYES"))
    }
}
