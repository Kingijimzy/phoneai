package com.phoneai.app

import org.junit.Assert.*
import org.junit.Test

class OrionCodingDiagnosticsTest {
    @Test fun extractsPythonTracebackFileAndLine() {
        val output = "Traceback (most recent call last):\n  File \"/workspace/projects/demo/main.py\", line 12, in run\n    value = missing\nNameError: name 'missing' is not defined"
        val result = OrionCodingDiagnostics.parse(output)
        assertTrue(result.any { it.category == "python_traceback" && it.file!!.endsWith("main.py") && it.line == 12 })
    }

    @Test fun extractsKotlinCompilerLocation() {
        val result = OrionCodingDiagnostics.parse("e: app/src/main/Foo.kt:27:9: error: unresolved reference: missing")
        assertTrue(result.any { it.category == "kotlin_compiler" && it.file == "app/src/main/Foo.kt" && it.line == 27 })
    }

    @Test fun extractsKotlinCompilerFileUriLocation() {
        val result = OrionCodingDiagnostics.parse("e: file:///workspace/app/src/Foo.kt:44:3: error: type mismatch")
        assertTrue(result.any { it.category == "kotlin_compiler" && it.file!!.endsWith("Foo.kt") && it.line == 44 })
    }

    @Test fun extractsPytestFailureAndBoundsOutput() {
        val result = OrionCodingDiagnostics.parse("tests/test_math.py:18: AssertionError in test_total")
        assertTrue(result.any { it.category == "pytest" && it.line == 18 })
        assertTrue(OrionCodingDiagnostics.render(result).contains("tests/test_math.py:18"))
    }

    @Test fun unknownOutputProducesExplicitFallback() {
        assertTrue(OrionCodingDiagnostics.render(emptyList()).contains("No structured"))
    }
}
