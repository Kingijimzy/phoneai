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
}
