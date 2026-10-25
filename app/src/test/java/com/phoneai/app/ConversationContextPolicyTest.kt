package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationContextPolicyTest {
    @Test fun mapsOnlyConversationRowsToProviderNeutralRoles() {
        val rows = listOf(
            "me" to "What is Orion?",
            "ai" to "Orion is your assistant.",
            "note" to "Opened WhatsApp",
            "user" to "What about trading?",
            "assistant" to "We can discuss trading."
        )

        assertEquals(
            listOf(
                "user" to "What is Orion?",
                "assistant" to "Orion is your assistant.",
                "user" to "What about trading?",
                "assistant" to "We can discuss trading."
            ),
            ConversationContextPolicy.recent(rows)
        )
    }

    @Test fun keepsOnlyMostRecentTurnsAndBoundsEachMessage() {
        val rows = (1..16).map { index ->
            (if (index % 2 == 0) "ai" else "me") to "message-$index"
        }
        val recent = ConversationContextPolicy.recent(rows, maxTurns = 12, maxChars = 6)

        assertEquals(12, recent.size)
        assertEquals("message-5", recent.first().second)
        assertEquals("message", recent.last().second)
    }

    @Test fun ignoresEmptyMessagesAndUnknownEventKinds() {
        assertEquals(
            listOf("user" to "hello"),
            ConversationContextPolicy.recent(
                listOf("note" to "status", "me" to " ", "me" to "hello", "system" to "secret")
            )
        )
    }
}
