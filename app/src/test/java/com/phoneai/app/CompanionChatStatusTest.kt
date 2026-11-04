package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionChatStatusTest {
    @Test fun mapsChatRuntimeStatesToCompanionAnimation() {
        assertEquals(CompanionView.Activity.IDLE, companionActivityForChatStatus("Ready"))
        assertEquals(CompanionView.Activity.LISTENING, companionActivityForChatStatus("Listening"))
        assertEquals(CompanionView.Activity.SPEAKING, companionActivityForChatStatus("Speaking"))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForChatStatus("Thinking"))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForChatStatus("Working"))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForChatStatus("Needs approval"))
        assertEquals(CompanionView.Activity.IDLE, companionActivityForChatStatus("Error"))
    }
}
