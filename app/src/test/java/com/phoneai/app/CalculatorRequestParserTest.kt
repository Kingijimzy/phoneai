package com.phoneai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorRequestParserTest {
    @Test fun extractsTheRequestedArithmeticExpressions() {
        val expressions = CalculatorRequestParser.parse(
            "Test 15 + 25, 100 / 4 and 2 ** 5."
        )
        assertEquals(listOf("15 + 25", "100 / 4", "2 ** 5"), expressions.map { it.display })
        assertEquals(40.0, expressions[0].expected()!!, 0.0)
        assertEquals(25.0, expressions[1].expected()!!, 0.0)
        assertEquals(32.0, expressions[2].expected()!!, 0.0)
    }

    @Test fun generatedScriptChecksEachRequestedExpressionAndAllOperations() {
        val expressions = CalculatorRequestParser.parse("15 + 25; 100 / 4; 2 ** 5")
        val script = CalculatorRequestParser.script(expressions)
        assertTrue(script.contains("print(\"Power:\""))
        assertTrue(script.contains("result_1 = 15 + 25"))
        assertTrue(script.contains("result_2 = 100 / 4"))
        assertTrue(script.contains("result_3 = 2 ** 5"))
        assertTrue(script.contains("PASS: 15 + 25"))
        assertTrue(script.contains("PASS: 100 / 4"))
        assertTrue(script.contains("PASS: 2 ** 5"))
        assertTrue(script.contains("PASS: division-by-zero guard"))
    }

    @Test fun ignoresUnsafeOrUnboundedExpressionInputs() {
        val expressions = CalculatorRequestParser.parse("15 + 25; 2 ** 999999; import os; 1 / 0")
        assertEquals(listOf("15 + 25", "1 / 0"), expressions.map { it.display })
    }
}
