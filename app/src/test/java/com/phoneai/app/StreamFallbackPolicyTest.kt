package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFallbackPolicyTest {
    @Test fun fallbackIsAllowedBeforeAnyVisibleText() {
        assertTrue(StreamFallbackPolicy.mayTryAnotherProvider(false, false))
    }

    @Test fun fallbackIsBlockedAfterVisibleTextToPreventDuplicates() {
        assertFalse(StreamFallbackPolicy.mayTryAnotherProvider(true, false))
    }

    @Test fun cancellationNeverFallsThroughToAnotherProvider() {
        assertFalse(StreamFallbackPolicy.mayTryAnotherProvider(false, true))
        assertFalse(StreamFallbackPolicy.mayTryAnotherProvider(true, true))
    }
}
