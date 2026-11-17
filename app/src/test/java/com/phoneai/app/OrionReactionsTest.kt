package com.phoneai.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionReactionsTest {
    @Test fun laughterGetsAnInstantReaction() {
        listOf("haha", "Hahaha!", "lol", "LMAO", "ha ha ha", "hehe").forEach { assertNotNull(it, OrionReactions.forUtterance(it)) }
    }
    @Test fun realRequestsAreNeverIntercepted() {
        listOf("haha open my calendar", "have a look at this", "hello", "what is the weather", "").forEach { assertNull(it, OrionReactions.forUtterance(it)) }
        assertFalse(OrionReactions.isPureLaughter("happy birthday"))
    }
}
