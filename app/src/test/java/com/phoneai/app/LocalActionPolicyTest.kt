package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class LocalActionPolicyTest {
    @Test fun deviceCommandsRequirePhoneControl() {
        listOf(
            "flashlight on", "volume up", "open the camera", "navigate to the airport",
            "open settings", "take a screenshot", "scroll down", "tap the send button",
            "open WhatsApp", "play music on YouTube", "set an alarm for 7am",
            "read the screen", "go home"
        ).forEach { assertTrue(it, LocalActionPolicy.requiresPhoneControl(it)) }
    }

    @Test fun localAnswerCommandsDoNotRequirePhoneControl() {
        listOf(
            "what time is it", "what's the date", "remember that I like tea",
            "what do you remember about me", "25 * 4", "take a note: buy milk",
            "what's the weather", "tell me a joke", "my notes"
        ).forEach { assertFalse(it, LocalActionPolicy.requiresPhoneControl(it)) }
    }
}
