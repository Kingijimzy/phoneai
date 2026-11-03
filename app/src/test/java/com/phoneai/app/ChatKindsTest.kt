package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class ChatKindsTest {
    @Test fun everyPromptChipHasAKind() {
        ChatGroups.coach.flatMap { it.chips }.filter { it.prompt != null }.forEach {
            assertNotNull("no kind for chip " + it.label, ChatKinds.forChip(it.label))
        }
    }
    @Test fun freeTextHasNoKindExceptTradeReview() {
        assertNull(ChatKinds.forText("hello"))
        assertEquals(ChatKinds.Kind.REVIEW, ChatKinds.forText("Review my trade: BTC long"))
    }
    @Test fun kindsHaveDistinctLabels() {
        val labels = ChatKinds.Kind.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }
}
