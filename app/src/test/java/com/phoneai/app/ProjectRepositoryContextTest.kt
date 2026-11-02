package com.phoneai.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectRepositoryContextTest {
    @Test fun resolvesNamedPhoneAiProjectToItsExactPublicRepository() {
        val context = ProjectRepositoryContext.forRequest("Inspect my PhoneAI project and find a real weakness.")
        assertTrue(context.orEmpty().contains("Kingijimzy/phoneai"))
        assertTrue(context.orEmpty().contains("section=tree"))
    }

    @Test fun doesNotInjectRepositoryForUnrelatedRequests() {
        assertNull(ProjectRepositoryContext.forRequest("What is the weather today?"))
    }
}
