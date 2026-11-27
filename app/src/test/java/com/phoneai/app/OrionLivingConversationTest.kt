package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OrionLivingConversationTest {
    private fun line(content: OrionMessageContent, text: String = "") =
        OrionChatLine(id = 1L, kind = "ai", text = text, content = content)

    @Test fun structuredChartIsRecognizedAsItsOwnConversationPresentation() {
        val chart = OrionMessageContent(blocks = listOf(
            OrionMessageBlock.Text("Here is the comparison."),
            OrionMessageBlock.Chart("Weekly volume", "line", listOf("Mon", "Tue"), listOf(10.0, 14.0), "trades")
        ))
        assertEquals(OrionReplyType.CHART, classifyOrionReply(line(chart)))
    }

    @Test fun ordinaryConversationIsNotPromotedToAVisualOrTaskCard() {
        val prose = OrionMessageContent(blocks = listOf(
            OrionMessageBlock.Text("We can discuss the chart idea without creating one.")
        ))
        assertEquals(OrionReplyType.NORMAL, classifyOrionReply(line(prose)))
    }

    @Test fun codeAndResearchKeepTheirExistingPresentationTypes() {
        val code = OrionMessageContent(blocks = listOf(OrionMessageBlock.Code("kotlin", null, "val x = 1")))
        val research = OrionMessageContent(blocks = listOf(OrionMessageBlock.Research("Summary", listOf("https://example.com"))))
        assertEquals(OrionReplyType.CODE, classifyOrionReply(line(code)))
        assertEquals(OrionReplyType.RESEARCH, classifyOrionReply(line(research)))
    }

    @Test fun activityNotesRemainTaskCards() {
        val note = line(OrionMessageContent(blocks = listOf(OrionMessageBlock.Text("Working")))).copy(kind = "note")
        assertEquals(OrionReplyType.TASK, classifyOrionReply(note))
    }
}
