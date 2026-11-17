package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrionChatPolishTest {
    @Test fun longCodeStaysOutOfChat() {
        val code = (1..20).joinToString("\n") { "line$it" }
        val out = OrionChatText.tidy("Here you go\n```kotlin\n$code\n```\nDone")
        assertTrue(out.contains("20 lines"))
        assertTrue(!out.contains("line5"))
        assertTrue(out.startsWith("Here you go") && out.endsWith("Done"))
    }

    @Test fun shortCodeAndPlainTextAreUntouched() {
        val short = "Use:\n```sh\nls -la\n```"
        assertEquals(short, OrionChatText.tidy(short))
        assertEquals("hello", OrionChatText.tidy("hello"))
    }

    @Test fun unfinishedFenceShowsWritingNotCode() {
        assertTrue(OrionChatText.tidy("ok\n```py\nprint(1)\nprint(2)").contains("writing code"))
    }

    @Test fun liveCardShowsOnlyTheTail() {
        val tail = OrionLiveFloatPolicy.tail((1..10).joinToString("\n") { "out$it" } + "\n\n")
        assertEquals(listOf("out7", "out8", "out9", "out10"), tail)
        assertTrue(OrionLiveFloatPolicy.tail("x".repeat(200)).single().length <= 52)
    }

    @Test fun geminiOnlyGetsTheGistOfLongResults() {
        assertEquals("Done.", GeminiLivePolicy.clipToolResult("  "))
        val clipped = GeminiLivePolicy.clipToolResult("word ".repeat(400))
        assertTrue(clipped.length < 800 && clipped.contains("full details are in the chat"))
        assertEquals("short", GeminiLivePolicy.clipToolResult("short"))
    }
}
