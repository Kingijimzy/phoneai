package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionChatStatusTest {
    @Test fun mapsPersistedToolEventsToDistinctCompanionPerformances() {
        fun event(kind: OrionActivityKind, title: String, summary: String = "", status: OrionActivityStatus = OrionActivityStatus.ACTIVE) =
            OrionActivityEvent("e-${kind.name}-${title.hashCode()}", "task-1", kind, status, title, summary, 100L)

        assertEquals(CompanionView.Activity.BROWSING,
            companionActivityForEvent(event(OrionActivityKind.RESEARCH, "Browser search")))
        assertEquals(CompanionView.Activity.BUILDING,
            companionActivityForEvent(event(OrionActivityKind.CODING, "Writing Kotlin")))
        assertEquals(CompanionView.Activity.DRAWING,
            companionActivityForEvent(event(OrionActivityKind.TOOL, "tool=sketch_screen; outcome=running")))
        assertEquals(CompanionView.Activity.RUNNING,
            companionActivityForEvent(event(OrionActivityKind.TOOL, "Python terminal")))
        assertEquals(CompanionView.Activity.ACTING,
            companionActivityForEvent(event(OrionActivityKind.TOOL, "Opening app")))
        assertEquals(CompanionView.Activity.RUNNING,
            companionActivityForEvent(event(OrionActivityKind.DELEGATION, "Worker started")))
        val warning = event(OrionActivityKind.APPROVAL, "Risk notice", status = OrionActivityStatus.WAITING)
        assertEquals("WARNING", companionStatusForEvent(warning))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForEvent(warning))
        assertEquals("ERROR", companionStatusForEvent(event(OrionActivityKind.ERROR, "Failed", status = OrionActivityStatus.FAILED)))
    }

    @Test fun mapsChatRuntimeStatesToCompanionAnimation() {
        assertEquals(CompanionView.Activity.IDLE, companionActivityForChatStatus("Ready"))
        assertEquals(CompanionView.Activity.LISTENING, companionActivityForChatStatus("Listening"))
        assertEquals(CompanionView.Activity.SPEAKING, companionActivityForChatStatus("Speaking"))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForChatStatus("Thinking"))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForChatStatus("Working"))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForChatStatus("Warning"))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForChatStatus("Needs approval"))
        assertEquals(CompanionView.Activity.THINKING, companionActivityForChatStatus("Needs approval"))
        assertEquals(CompanionView.Activity.IDLE, companionActivityForChatStatus("Error"))
        assertEquals(CompanionView.Activity.BROWSING, companionActivityForChatStatus("Browsing"))
        assertEquals(CompanionView.Activity.BUILDING, companionActivityForChatStatus("Building"))
        assertEquals(CompanionView.Activity.ACTING, companionActivityForChatStatus("Using phone"))
        assertEquals(CompanionView.Activity.DRAWING, companionActivityForChatStatus("Sketching"))
        assertEquals(CompanionView.Activity.WRITING, companionActivityForChatStatus("Writing"))
        assertEquals(CompanionView.Activity.RUNNING, companionActivityForChatStatus("Running"))
        assertEquals(CompanionView.Activity.LISTENING, companionActivityForChatStatus("Recording"))
    }
}
