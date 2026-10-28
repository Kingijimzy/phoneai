package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class SharedToolSurfacePolicyTest {
    @Test fun wakePhraseExtractsOnlyTheCommandBody() {
        assertEquals("open WhatsApp", SharedToolSurfacePolicy.commandAfterWake("Orion, open WhatsApp"))
        assertEquals("set a timer for five minutes", SharedToolSurfacePolicy.commandAfterWake("Hey Orion: set a timer for five minutes"))
        assertNull(SharedToolSurfacePolicy.commandAfterWake("open WhatsApp"))
        assertNull(SharedToolSurfacePolicy.commandAfterWake("Orion"))
        assertNull(SharedToolSurfacePolicy.commandAfterWake("I was talking to Orion yesterday"))
    }

    @Test fun explicitExecutionRequestsFromSpecialistAgentsDelegateToSharedExecutor() {
        listOf(
            "Open WhatsApp",
            "Search the web for the latest Android release",
            "Set a timer for 5 minutes",
            "Send a Telegram message",
            "Remember this preference",
            "Show my saved tasks",
            "What is the current weather?",
            "What is the price of BTC?",
            "Check the latest model release"
        ).forEach { assertTrue("Expected delegation for: $it", SharedToolSurfacePolicy.shouldDelegate(it)) }
    }

    @Test fun ordinaryDiscussionDoesNotAutomaticallyDelegate() {
        listOf(
            "Tell me about opening apps on Android",
            "Explain why timers are useful",
            "What is a good trading strategy?",
            "Can we talk about the latest technology?"
        ).forEach { assertFalse("Unexpected delegation for: $it", SharedToolSurfacePolicy.shouldDelegate(it)) }
    }
}
