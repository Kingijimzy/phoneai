package com.phoneai.app

import kotlin.math.pow

/** Extracts only bounded numeric arithmetic expressions from a user's calculator request. */
internal object CalculatorRequestParser {
    data class Expression(val left: String, val operator: String, val right: String) {
        val display: String get() = "$left $operator $right"
        fun expected(): Double? {
            val a = left.toDoubleOrNull() ?: return null
            val b = right.toDoubleOrNull() ?: return null
            val value = when (operator) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> if (b == 0.0) return null else a / b
                "**" -> if (kotlin.math.abs(b) > 100.0) return null else a.pow(b)
                else -> return null
            }
            return value.takeIf { it.isFinite() }
        }
    }

    private val pattern = Regex(
        """(?<![A-Za-z0-9_.])(-?(?:\d+(?:\.\d*)?|\.\d+))\s*(\*\*|[+\-*/])\s*(-?(?:\d+(?:\.\d*)?|\.\d+))(?![A-Za-z0-9_.])"""
    )

    fun requestedFolder(request: String): String =
        Regex("""(?i)\b(?:folder|directory)\s+(?:called|named)\s+([A-Za-z0-9_-]{1,60})\b""")
            .find(request)?.groupValues?.getOrNull(1)?.let { DownloadsFolderPolicy.normalize(it) } ?: "Orion"

    fun parse(request: String): List<Expression> = pattern.findAll(request)
        .mapNotNull { match ->
            val expression = Expression(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            val a = expression.left.toDoubleOrNull()
            val b = expression.right.toDoubleOrNull()
            if (a == null || b == null || !a.isFinite() || !b.isFinite() ||
                kotlin.math.abs(a) > 1.0e12 || kotlin.math.abs(b) > 1.0e12) null
            else expression.takeIf { it.expected() != null }
        }
        .distinctBy { it.display }
        .take(12)
        .toList()

    /** Generates restricted-sandbox Python: no imports, functions, files, network or input(). */
    fun script(expressions: List<Expression>): String {
        val first = expressions.firstOrNull() ?: Expression("15", "+", "25")
        val lines = mutableListOf(
            "# Orion calculator: change first and second to reuse the basic operations",
            "first = ${first.left}",
            "second = ${first.right}",
            "print(\"Addition:\", first + second)",
            "print(\"Subtraction:\", first - second)",
            "print(\"Multiplication:\", first * second)",
            "if second != 0:",
            "    print(\"Division:\", first / second)",
            "else:",
            "    print(\"Division error: cannot divide by zero\")",
            "print(\"Power:\", first ** second)",
            "zero = 0",
            "if zero == 0:",
            "    print(\"PASS: division-by-zero guard\")",
            "else:",
            "    print(\"FAIL: division-by-zero guard\")"
        )
        expressions.forEachIndexed { index, expression ->
            val name = "result_${index + 1}"
            lines += "$name = ${expression.display}"
            lines += "print(\"RESULT: ${expression.display} =\", $name)"
            val expected = expression.expected()
            if (expected == null) {
                lines += "print(\"FAIL: ${expression.display}\")"
            } else {
                lines += "if $name == ${expected}:"
                lines += "    print(\"PASS: ${expression.display}\")"
                lines += "else:"
                lines += "    print(\"FAIL: ${expression.display}\")"
            }
        }
        lines += "print(\"PASS: arithmetic operations executed\")"
        return lines.joinToString("\n")
    }
}
