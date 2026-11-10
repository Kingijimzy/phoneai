package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionToolCallRecoveryTest {
    private val known = { n: String -> n == "python_exec" }

    @Test fun recoversTripleQuotedCall() {
        val r = OrionToolCallRecovery.recover("Let me run it.\npython_exec(code=\"\"\"print('hi')\"\"\")", known)!!
        assertEquals("python_exec", r.getString("tool"))
        assertEquals("print('hi')", r.getJSONObject("args").getString("code"))
    }

    @Test fun recoversDoubleQuotedCall() {
        val r = OrionToolCallRecovery.recover("python_exec(code=\"print(1)\")", known)!!
        assertEquals("print(1)", r.getJSONObject("args").getString("code"))
    }

    @Test fun ignoresUnknownToolsAndPlainText() {
        assertNull(OrionToolCallRecovery.recover("foo(bar=\"x\")", known))
        assertNull(OrionToolCallRecovery.recover("just talking", known))
    }

    @Test fun recoversDsmlInvoke() {
        val raw = "<\uFF5C\uFF5CDSML\uFF5C\uFF5C calls>\n<\uFF5C\uFF5CDSML\uFF5C\uFF5C invoke name=\"python_exec\">\n<\uFF5C\uFF5CDSML\uFF5C\uFF5C parameter name=\"code\" string=\"true\">print(1)</\uFF5C\uFF5CDSML\uFF5C\uFF5C parameter>\n</\uFF5C\uFF5CDSML\uFF5C\uFF5C invoke>"
        val r = OrionToolCallRecovery.recover(raw, known)!!
        assertEquals("python_exec", r.getString("tool"))
        assertEquals("print(1)", r.getJSONObject("args").getString("code"))
    }

    @Test fun detectsImageGenerationPseudoCallBeforeItLeaksIntoChat() {
        val knownMedia = { n: String -> n == "python_exec" || n == "image_generate" }
        val raw = "On it.\n\n\u0060\u0060\u0060python\nimage_generate(prompt=\"A police cat in New York\")\n\u0060\u0060\u0060"
        assertTrue(OrionToolCallRecovery.shouldHoldFromChat(raw, knownMedia))
        val recovered = OrionToolCallRecovery.recover(raw, knownMedia)!!
        assertEquals("image_generate", recovered.getString("tool"))
        assertEquals("A police cat in New York", recovered.getJSONObject("args").getString("prompt"))
    }

    @Test fun ordinaryCodeIsBufferedButNotMistakenForAToolCall() {
        val raw = "\u0060\u0060\u0060python\\nprint('hello')\\n\u0060\u0060\u0060"
        assertTrue(OrionToolCallRecovery.shouldHoldFromChat(raw, known))
        assertNull(OrionToolCallRecovery.recover(raw, known))
    }

    @Test fun ordinaryConversationDoesNotNeedToBeBuffered() {
        assertFalse(OrionToolCallRecovery.shouldHoldFromChat("Hello, how can I help?", known))
    }

    @Test fun recoversPositionalFencedCall() {
        val raw = "\u0060\u0060\u0060python\nprint('hello')\n\u0060\u0060\u0060"
        val r = OrionToolCallRecovery.recover(raw, known)!!
        assertEquals("python_exec", r.getString("tool"))
        assertTrue(r.getJSONObject("args").getString("code").contains("import os"))
    }
}
