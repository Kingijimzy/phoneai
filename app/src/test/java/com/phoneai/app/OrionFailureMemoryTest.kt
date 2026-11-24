package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionFailureMemoryTest {
    @Test fun sameErrorWithDifferentNumbersCollapsesToOneSignature() {
        val a = OrionFailureMemory.signature("HTTP 404 for https://api.github.com/repos/x/y/pulls/123")
        val b = OrionFailureMemory.signature("HTTP 404 for https://api.github.com/repos/x/y/pulls/456")
        assertEquals(a, b)
    }

    @Test fun differentCausesStaySeparate() {
        val a = OrionFailureMemory.signature("HTTP 404 not found")
        val b = OrionFailureMemory.signature("HTTP 401 unauthorized")
        assertNotEquals(a, b)
    }
}
